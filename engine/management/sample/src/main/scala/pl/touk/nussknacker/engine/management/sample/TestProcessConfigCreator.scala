package pl.touk.nussknacker.engine.management.sample

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}

import argonaut.{Argonaut, Json}
import com.typesafe.config.Config
import org.apache.flink.api.common.functions.{MapFunction, RichMapFunction}
import org.apache.flink.api.common.state.{ValueState, ValueStateDescriptor}
import org.apache.flink.api.common.typeinfo.TypeInformation
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.sink.SinkFunction
import org.apache.flink.streaming.api.functions.source.SourceFunction
import org.apache.flink.streaming.api.functions.source.SourceFunction.SourceContext
import org.apache.flink.streaming.api.functions.timestamps.BoundedOutOfOrdernessTimestampExtractor
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.windowing.time.Time
import org.apache.flink.streaming.util.serialization.{KeyedSerializationSchema, SimpleStringSchema}
import pl.touk.nussknacker.engine.api._
import pl.touk.nussknacker.engine.api.exception.{EspExceptionHandler, ExceptionHandlerFactory}
import pl.touk.nussknacker.engine.api.lazyy.UsingLazyValues
import pl.touk.nussknacker.engine.api.process._
import pl.touk.nussknacker.engine.api.test.{NewLineSplittedTestDataParser, TestParsingUtils}
import pl.touk.nussknacker.engine.flink.api.process._
import pl.touk.nussknacker.engine.flink.util.exception.VerboselyLoggingExceptionHandler
import pl.touk.nussknacker.engine.kafka.{KafkaConfig, KafkaSinkFactory, KafkaSourceFactory}
import pl.touk.nussknacker.engine.management.sample.signal.{RemoveLockProcessSignalFactory, SampleSignalHandlingTransformer}

import scala.concurrent.Future
import argonaut.Argonaut._
import argonaut.ArgonautShapeless._

class TestProcessConfigCreator extends ProcessConfigCreator {


  override def sinkFactories(config: Config) = {
    val kConfig = KafkaConfig(config.getString("kafka.zkAddress"), config.getString("kafka.kafkaAddress"), None, None)

    val sendSmsSink = EmptySink
    val monitorSink = EmptySink
    Map(
      "sendSms" -> WithCategories(SinkFactory.noParam(sendSmsSink), "Category1"),
      "monitor" -> WithCategories(SinkFactory.noParam(monitorSink), "Category1", "Category2"),
      "kafka-string" -> WithCategories(new KafkaSinkFactory(kConfig,
        new KeyedSerializationSchema[Any] {
          override def serializeValue(element: Any) = element.toString.getBytes(StandardCharsets.UTF_8)

          override def serializeKey(element: Any) = null

          override def getTargetTopic(element: Any) = null
        }), "Category1", "Category2")
    )
  }

  override def listeners(config: Config) = List()

  override def sourceFactories(config: Config) = {
    val kConfig = KafkaConfig(config.getString("kafka.zkAddress"), config.getString("kafka.kafkaAddress"), None, None)

    Map(
      "real-kafka" -> WithCategories(new KafkaSourceFactory[String](kConfig,
        new SimpleStringSchema, None, TestParsingUtils.newLineSplit), "Category1", "Category2"),
      "kafka-transaction" -> WithCategories(FlinkSourceFactory.noParam(prepareNotEndingSource, Some(new NewLineSplittedTestDataParser[String] {
        override def parseElement(testElement: String): String = testElement
      })), "Category1", "Category2"),
      "oneSource" -> WithCategories(FlinkSourceFactory.noParam(new FlinkSource[String] {

        override def timestampAssigner = None

        override def toFlinkSource = new SourceFunction[String] {

          var run = true

          var emited = false

          override def cancel() = {
            run = false
          }

          override def run(ctx: SourceContext[String]) = {
            while (run) {
              if (!emited) ctx.collect("One element")
              emited = true
              Thread.sleep(1000)
            }
          }
        }

        override def typeInformation = implicitly[TypeInformation[String]]
      }), "Category1", "Category2"),
      "csv-source" -> WithCategories(FlinkSourceFactory.noParam(new FlinkSource[CsvRecord] with TestDataGenerator {
        override def typeInformation = implicitly[TypeInformation[CsvRecord]]

        override def toFlinkSource = new SourceFunction[CsvRecord] {
          override def cancel() = {}

          override def run(ctx: SourceContext[CsvRecord]) = {}

        }

        override def generateTestData(size: Int) = "record1|field2\nrecord2|field3".getBytes(StandardCharsets.UTF_8)

        override def timestampAssigner = None

      }, Some(new NewLineSplittedTestDataParser[CsvRecord] {
        override def parseElement(testElement: String): CsvRecord = CsvRecord(testElement.split("\\|").toList)
      })), "Category1", "Category2")
    )

  }


  //this not ending source is more reliable in tests than CollectionSource, which terminates quickly
  def prepareNotEndingSource: FlinkSource[String] = {
    new FlinkSource[String] {
      override def typeInformation = implicitly[TypeInformation[String]]

      override def timestampAssigner = Option(new BoundedOutOfOrdernessTimestampExtractor[String](Time.minutes(10)) {
        override def extractTimestamp(element: String): Long = System.currentTimeMillis()
      })

      override def toFlinkSource = new SourceFunction[String] {
        var running = true
        var counter = new AtomicLong()
        val afterFirstRun = new AtomicBoolean(false)

        override def cancel() = {
          running = false
        }

        override def run(ctx: SourceContext[String]) = {
          val r = new scala.util.Random
          while (running) {
            if (afterFirstRun.getAndSet(true)) {
              ctx.collect("TestInput" + r.nextInt(10))
            } else {
              ctx.collect("TestInput1")
            }
            Thread.sleep(2000)
          }
        }
      }
    }
  }

  override def services(config: Config) = {
    Map(
      "accountService" -> WithCategories(EmptyService, "Category1"),
      "componentService" -> WithCategories(EmptyService, "Category1", "Category2"),
      "transactionService" -> WithCategories(EmptyService, "Category1"),
      "serviceModelService" -> WithCategories(EmptyService, "Category1", "Category2"),
      "paramService" -> WithCategories(OneParamService, "Category1"),
      "enricher" -> WithCategories(Enricher, "Category1", "Category2"),
      "multipleParamsService" -> WithCategories(MultipleParamsService, "Category1", "Category2")

    )
  }

  override def customStreamTransformers(config: Config) = {
    val kConfig = KafkaConfig(config.getString("kafka.zkAddress"), config.getString("kafka.kafkaAddress"), None, None)
    val signalsTopic = config.getString("signals.topic")
    Map(
      "stateful" -> WithCategories(StatefulTransformer, "Category1", "Category2"),
      "customFilter" -> WithCategories(CustomFilter, "Category1", "Category2"),
      "constantStateTransformer" -> WithCategories(ConstantStateTransformer[String](ConstantState("stateId", 1234, List("elem1", "elem2", "elem3")).asJson.nospaces), "Category1", "Category2"),
      "constantStateTransformerLongValue" -> WithCategories(ConstantStateTransformer[Long](12333), "Category1", "Category2"),

      "lockStreamTransformer" -> WithCategories(new SampleSignalHandlingTransformer.LockStreamTransformer(), "Category1", "Category2")
    )
  }

  override def signals(config: Config) = {
    val kConfig = KafkaConfig(config.getString("kafka.zkAddress"), config.getString("kafka.kafkaAddress"), None, None)
    val signalsTopic = config.getString("signals.topic")
    Map(
      "removeLockSignal" -> WithCategories(new RemoveLockProcessSignalFactory(kConfig, signalsTopic), "Category1", "Category2")
    )
  }

  override def exceptionHandlerFactory(config: Config) = ParamExceptionHandler

  override def globalProcessVariables(config: Config) = Map(
    "DATE" -> WithCategories(DateProcessHelper.getClass, "Category1", "Category2")
  )

  override def buildInfo(): Map[String, String] = {
    Map(
      "process-version" -> "0.1",
      "engine-version" -> "0.1"
    )
  }
}

case object StatefulTransformer extends CustomStreamTransformer {

  @MethodToInvoke
  def execute(@ParamName("keyBy") keyBy: LazyInterpreter[String])
  = FlinkCustomStreamTransformation((start: DataStream[InterpretationResult]) => {
    start.keyBy(keyBy.syncInterpretationFunction)
      .mapWithState[ValueWithContext[Any], List[String]] { case (StringFromIr(ir, sr), oldState) =>
      val nList = sr :: oldState.getOrElse(Nil)
      (ValueWithContext(nList, ir.finalContext), Some(nList))
    }
  })

  object StringFromIr {
    def unapply(ir: InterpretationResult) = Some(ir, ir.finalContext.apply[String]("input"))
  }

}

case class ConstantStateTransformer[T:TypeInformation](defaultValue: T) extends CustomStreamTransformer {


  final val stateName = "constantState"

  @MethodToInvoke
  @QueryableStateNames(values = Array(stateName))
  def execute() = FlinkCustomStreamTransformation((start: DataStream[InterpretationResult]) => {
    start
      .keyBy(_ => "1")
      .map(new RichMapFunction[InterpretationResult, ValueWithContext[Any]] {

        var constantState: ValueState[T] = _

        override def open(parameters: Configuration): Unit = {
          super.open(parameters)
          val descriptor = new ValueStateDescriptor[T]("constantState", implicitly[TypeInformation[T]])
          descriptor.setQueryable(stateName)
          constantState = getRuntimeContext.getState(descriptor)
        }

        override def map(value: InterpretationResult): ValueWithContext[Any] = {
          constantState.update(defaultValue)
          ValueWithContext[Any](value, value.finalContext)
        }
      })
  })
}

case object CustomFilter extends CustomStreamTransformer {

  @MethodToInvoke(returnType = classOf[Void])
  def execute(@ParamName("expression") expression: LazyInterpreter[Boolean])
   = FlinkCustomStreamTransformation((start: DataStream[InterpretationResult]) =>
      start.filter(expression.syncInterpretationFunction).map(ValueWithContext(_)))

}

case object ParamExceptionHandler extends ExceptionHandlerFactory {
  @MethodToInvoke
  def create(@ParamName("param1") param: String, metaData: MetaData): EspExceptionHandler = VerboselyLoggingExceptionHandler(metaData)

}

case object EmptySink extends FlinkSink {

  override def testDataOutput: Option[(Any) => String] = Option {
    case a: Displayable => a.display.spaces2
    case b => b.toString
  }

  override def toFlinkFunction: SinkFunction[Any] = new SinkFunction[Any] {
    override def invoke(value: Any): Unit = ()
  }
}

case object EmptyService extends Service {
  @MethodToInvoke
  def invoke() = Future.successful(Unit)
}

case object OneParamService extends Service {
  @MethodToInvoke
  def invoke(@PossibleValues(value = Array("a", "b", "c")) @ParamName("param") param: String) = Future.successful(param)
}

case object Enricher extends Service {
  @MethodToInvoke
  def invoke(@ParamName("param") param: String) = Future.successful(RichObject(param, 123L, Some("rrrr")))
}

case class RichObject(field1: String, field2: Long, field3: Option[String])

case class CsvRecord(fields: List[String]) extends UsingLazyValues with Displayable {

  lazy val firstField = fields.head

  lazy val enrichedField = lazyValue[RichObject]("enricher", "param" -> firstField)

  override def display = Argonaut.jObjectFields("firstField" -> Json.jString(firstField))

  override def originalDisplay: Option[String] = Some(fields.mkString("|"))
}

case object MultipleParamsService extends Service {
  @MethodToInvoke
  def invoke(@ParamName("foo") foo: String,
             @ParamName("bar") bar: String,
             @ParamName("baz") baz: String,
             @ParamName("quax") quax: String) = Future.successful(Unit)
}

object DateProcessHelper {
  def nowTimestamp(): Long = System.currentTimeMillis()
}

case class ConstantState(id: String, transactionId: Int, elements: List[String])
