#Intro

Before designing your own processes first you have to define model of your data 
and means to retrieve them.  

#Defining and discovering model

All classes of your model should be packed in jar including implementation of 'ProcessConfigCreator'.
This trait defines nodes which can be used to build Flink jobs. 
Nodes operates on data structures, which you also have to provide in model. 

#ProcessConfigCreator - entry point of model

Nussknacker accesses your model by implementation of
`pl.touk.nussknacker.engine.api.process.ProcessConfigCreator`

```scala
trait ProcessConfigCreator {

  def customStreamTransformers(config: Config): Map[String, WithCategories[CustomStreamTransformer]]

  def services(config: Config) : Map[String, WithCategories[Service]]

  def sourceFactories(config: Config): Map[String, WithCategories[SourceFactory[_]]]

  def sinkFactories(config: Config): Map[String, WithCategories[SinkFactory]]

  def listeners(config: Config): Seq[ProcessListener]

  def exceptionHandlerFactory(config: Config) : ExceptionHandlerFactory

  def globalProcessVariables(config: Config): Map[String, WithCategories[AnyRef]]

  def buildInfo(): Map[String, String]

  def signals(config: Config): Map[String, WithCategories[ProcessSignalSender]]

}
```

##Passing ProcessConfigCreator to Nussknacker
`ProcessConfigCreator` implementation is provided by 
[ServiceLoader](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html).
To register implementation, add file `META-INF/services/pl.touk.nussknacker.engine.api.process.ProcessConfigCreator`
containing FQN of class implementing`ProcessConfigCreator` interface.
Exactly one implementation have to be provided. 
Any other case will result throws exception.
##Categories

Each process is assigned to exactly one category. Each node can be accessible in many categories? 
Category defines if node is accessible in process. 

TODO: some code example.

##Creating sources and sinks

##Source
To create source one needs to extend `pl.touk.nussknacker.engine.api.process.SourceFactory` interface and annotate one method with
`@MethodToInvoke` and return `Source[T]`.

Nussknacker comes with kafka source `pl.touk.nussknacker.engine.kafka.KafkaSourceFactory` which uses `org.apache.flink.streaming.connectors.kafka.FlinkKafkaConsumer09` underneath. 
Let's see how it works
```scala
class KafkaSourceFactory[T: TypeInformation](config: KafkaConfig,
                                             schema: DeserializationSchema[T],
                                             val timestampAssigner: Option[TimestampAssigner[T]],
                                             ...) extends FlinkSourceFactory[T] with Serializable {
  @MethodToInvoke
  def create(processMetaData: MetaData, @ParamName("topic") topic: String): Source[T] with TestDataGenerator = {
    ...
    new KafkaSource(consumerGroupId = processMetaData.id, topic = topic)
  }
}
```
First let's look at constructor params.
`config` and `schema` is pretty straightforward. 
`timestampAssigner` extracts event time from event, so Flink knows event time that can be used in aggregations. 
Event Time is Flink's crucial concept, see [Event Time](https://ci.apache.org/projects/flink/flink-docs-release-{{book.flinkMajorVersion}}/dev/event_time.html). 
`Metadata` parameter is injected by Nussknacker and `topic` is just normal parameter that user can set in UI.

### Source test data
To be able to test process from file `SourceFactory.testDataParser: Option[TestDataParser[T]]` methods has to be implemented.

`Source` can also specify how test data is generated - this can be triggered on `generate` button click - see [Quickstart section](Quickstart.md) for details.
Data generation logic is provided by extending `pl.touk.nussknacker.engine.api.process.TestDataGenerator`. 
See `KafkaSourceFactory` implementation for details.

##Sink
`Sink` creation is similar to `Source` creation. 
So one needs to extend `pl.touk.nussknacker.engine.api.process.SinkFactory` interface and annotate one method with `@MethodToInvoke` and return `Sink`.
Nussknacker comes with kafka sink `pl.touk.nussknacker.engine.kafka.KafkaSinkFactory` which uses `org.apache.flink.streaming.connectors.kafka.FlinkKafkaProducer09` underneath. 

##Defining model classes

##Creating service
To create service one needs to extend `pl.touk.nussknacker.engine.api.Service` interface and annotate one method with
`@MethodToInvoke` and return `scala.concurrent.Future` (method name is not important). Annotated method will be invoked by Nussknacker for every element passing through this service.
Service can do anything - it could be HTTP call or some static mapping.

Service divides in processors and enrichers. Processor does not return anything and enricher return some value. 
So to be classified as `Processor` annotated method has to return `Future[Unit]` in other case service will be classified as `Enricher`.

Sample enricher looks like this:
```scala
class ClientService extends Service {
  @MethodToInvoke
  def invoke(@ParamName("clientId") clientId: String)
            (implicit ec: ExecutionContext, metaData: MetaData): Future[Client] = {
    val clients = Map(
      "1" -> Client("1", "Alice", "123"), 
      "2" -> Client("1", "Bob", "234") 
    )
    Future { clients.get(clientId) }
  }
}
```
Every non-implicit parameter should be annotated with `@ParamName` - value from annotation will be used in UI to display parameter name.
You have full control over non-implicit parameters, on the other hand implicit parameters are injected by Nussknacker.

Apart from parameters - non-implicit and annotated with `@ParamName` you can also have certain implicit parameters in 
signature of `@MethodToInvoke`. They are optional - you can put any of them in method signature, but you don't have to.
They are:
* `scala.concurrent.ExecutionContext` - useful for e.g. invoking asynchronous external services
* `pl.touk.nussknacker.engine.api.MetaData` - if you want to e.g. have process name passed to external services
* `pl.touk.nussknacker.engine.api.test.ServiceInvocationCollector` - used in test mode, see next paragraph

### Service in tests from file
Service in tests can behave as in production, but sometimes it seems wrong or impractical - for example when service sends mail. 
So Nussknacker gives you control to decide how your service behaves during tests from file.
Here's an example:

```scala
class SendMailProcessor extends Service {
  @MethodToInvoke
  def invoke(@ParamName("mailAddress") mailAddress: String)
            (implicit ex: ExecutionContext, collector: ServiceInvocationCollector): Future[Unit] = {
    Future {
      if (collector.collectorEnabled) {
        collector.collect(s"We are in test mode! Pretend to send mail to ${mailAddress}")
      } else {
        sendMail(mailAddress)
      }
    }
  }
}
```

`SendMailProcessor` uses `ServiceInvocationCollector` which is enabled in test mode. 
Collected value can be seen in UI in mocked service after tests, so for example you can use `collector.collect()` 
to collect HTTP request that you would normally send in production.

###Lazy values (use with caution)
Instead of using `Enricher` node in graph to enrich data, one can use some syntactic sugar to enrich data in more object-oriented way -it is possible to inject enricher into model class. 

Here's an example.

So let's say we defined `ClientService` as in above, and then we passed it into `ProcessConfigCreator`:
```scala
class MyProcessConfigCreator extends ProcessConfigCreator {
    ...
    override def services(config: Config): Map[String, WithCategories[Service]] = {
        Map("clientService" -> all(new ClientService))
    }
    ...
}
```
To enrich model, which we assume is `Transaction` with `clientId` field, normally we would put `Enricher` in UI graph, but instead
you can inject enricher:
```scala
case class Transaction(clientId: String) extends UsingLazyValues {
  lazy val client = lazyValue[Client]("clientService", "clientId" -> clientId)
}
```
After that you'll able to access `client` in expression like this `#input.client` - so potential HTTP call is made behind a scenes, 
but user cannot really distinguish it from usual field access. 

##Exception handlers

##Common caveats

#Advanced subjects

##Global variables

##Signals

##Custom stream transformers

##Process listeners

##Custom processes