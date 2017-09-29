package pl.touk.nussknacker.ui.security

import org.scalatest.{FlatSpec, Matchers}

import scala.util.{Failure, Success, Try}
import akka.http.scaladsl.server.directives.{Credentials, SecurityDirectives}
import pl.touk.nussknacker.ui.security.api.LoggedUser
import com.typesafe.config.{Config, ConfigFactory}
import org.scalatest.prop.{TableDrivenPropertyChecks, TableFor2}
import pl.touk.nussknacker.ui.security.api.AuthenticatorFactory

class AuthenticatorProviderSpec extends FlatSpec with Matchers with TableDrivenPropertyChecks {

  import AuthenticatorProviderSpec._

  behavior of "AuthenticatorProvider.chooseAuthenticator"
  it should "choose authenticator from implementations" in {
    val table: TableFor2[List[AuthenticatorFactory], Try[AuthenticatorFactory]] = Table(
      ("authenticator factories", "chosen factory"),
      (Nil, Success(SimpleAuthenticatorFactory())),
      (DummyAuthenticatorFactory() :: Nil, Success(DummyAuthenticatorFactory())),
      (DummyAuthenticatorFactory2() :: Nil, Success(DummyAuthenticatorFactory2())),
      (DummyAuthenticatorFactory() :: DummyAuthenticatorFactory2() :: Nil, Failure(new IllegalArgumentException))
    )
    forAll(table) {
      (factories: List[AuthenticatorFactory], chosen: Try[AuthenticatorFactory]) => {
        val result = AuthenticatorProvider.chooseAuthenticator(new SimpleAuthenticatorFactory, factories)
        (chosen, result) match {
          case (_: Failure[_], _: Failure[_]) => succeed
          case (s1: Success[_], s2: Success[_]) => s1 shouldBe s2
          case _ => fail()
        }
      }
    }
  }

  it should "return default authentication provider" in {
    val conf = ConfigFactory.defaultApplication()
    AuthenticatorProvider(conf, this.getClass.getClassLoader) shouldBe a[SimpleAuthenticator]
  }

  object AuthenticatorProviderSpec {

    class DummyAuthenticator extends SecurityDirectives.Authenticator[LoggedUser] {
      override def apply(credentials: Credentials): Option[LoggedUser] = None
    }

    case class DummyAuthenticatorFactory() extends AuthenticatorFactory {
      override def createAuthenticator(config: Config) = new DummyAuthenticator
    }

    class DummyAuthenticator2 extends SecurityDirectives.Authenticator[LoggedUser] {
      override def apply(credentials: Credentials): Option[LoggedUser] = None
    }

    case class DummyAuthenticatorFactory2() extends AuthenticatorFactory {
      override def createAuthenticator(config: Config) = new DummyAuthenticator2
    }


  }

}