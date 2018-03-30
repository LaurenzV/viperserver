/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package viper.server


import java.lang.reflect.Constructor
import java.nio.file.Paths

import scala.collection.mutable.ListBuffer
import akka.actor.ActorRef
import com.typesafe.scalalogging.LazyLogging
import viper.carbon.CarbonFrontend
import viper.server.ViperServerRunner.ReporterActor
import viper.silicon.SiliconFrontend
import viper.silver.ast.{Position, _}
import viper.silver.frontend.{SilFrontend, TranslatorState}
import viper.silver.reporter
import viper.silver.reporter.{Reporter, _}
import viper.silver.verifier.errors._
import viper.silver.verifier.{AbstractVerificationError, _}

import scala.language.postfixOps

// Implementation of the Reporter interface used by the backend.
class ActorReporter(private val actor_ref: ActorRef, val tag: String)
  extends viper.silver.reporter.Reporter {

  val name = s"ViperServer_$tag"

  def report(msg: reporter.Message) = {
    //println(s"ActorReporter reporting >>> ${msg}")
    actor_ref ! ReporterActor.ServerRequest(msg)
  }
}

class ViperServerException extends Exception

case class ViperServerWrongTypeException(name: String) extends ViperServerException {
  override def toString: String = s"Verification backend (a.k.a. SiliconFrontend) $name."
}

case class ViperServerBackendNotFoundException(name: String) extends ViperServerException {
  override def toString: String = s"Verification backend (a.k.a. SiliconFrontend) $name could not be not found."
}

class VerificationWorker(val _reporter: ActorRef, val command: List[String])
  extends Runnable with LazyLogging {


  private def resolveCustomBackend(clazzName: String, rep: Reporter): Option[SilFrontend] = {
    (try {
      //val clazz: Class[_] = Class.forName(clazzName)
      //val arg_decls = Array[Class[_]](classOf[Reporter])
      val constructor = Class.forName(clazzName).getConstructor(classOf[viper.silver.reporter.Reporter])
      //Class.forName("viper.silicon.SiliconFrontend").getConstructors()(0)
      Some(constructor.newInstance(rep))
    } catch {
      case e: ClassNotFoundException => None
    }) match {
      case Some(instance) if instance.isInstanceOf[SilFrontend] =>
        Some(instance.asInstanceOf[SilFrontend])
      case Some(instance) =>
        throw ViperServerWrongTypeException(instance.getClass.getName)
      case _ =>
        throw ViperServerBackendNotFoundException(clazzName)
    }
  }

  private var _backend: ViperBackend = _

  def run(): Unit = {
    try {
      command match {
        case "silicon" :: args =>
          _backend = new ViperBackend(new SiliconFrontend(new ActorReporter(_reporter, "silicon")))
          _backend.execute(args)
        case "carbon" :: args =>
          _backend = new ViperBackend(new CarbonFrontend(new ActorReporter(_reporter, "carbon")))
          _backend.execute(args)
        case custom :: args =>
          _backend = new ViperBackend(resolveCustomBackend(custom, new ActorReporter(_reporter, custom)).get)
          _backend.execute(args)
        case args =>
          logger.info("invalid arguments: ${args.toString}",
            "You need to specify the verification backend, e.g., `silicon [args]`")
      }
    } catch {
      //case _: InterruptedException =>
      //case _: java.nio.channels.ClosedByInterruptException =>
      case e: Exception =>
        _reporter ! ReporterActor.ServerRequest(reporter.ExceptionReport(e))
        //e.printStackTrace(System.err)
    } finally {
      try {
        _backend.stop
      } catch {
        case _: Throwable =>
      }
    }
    if (_backend != null) {
      _reporter ! ReporterActor.FinalServerRequest
    } else {
      _reporter ! ReporterActor.FinalServerRequest
      println(s"The command $command did not result in initialization of verification backend.")
    }
  }
}

class ViperBackend(private val _frontend: SilFrontend) {

  private def collectDefinitions(program: Program): List[Definition] = (program.members.collect {

    case t: Method =>
      (Definition(t.name, "Method", t.pos) +: (t.pos match {
        case p: AbstractSourcePosition =>
          t.formalArgs.map { arg => Definition(arg.name, "Argument", arg.pos, Some(p)) } ++
            t.formalReturns.map { arg => Definition(arg.name, "Return", arg.pos, Some(p)) }
        case _ => Seq()
      })) ++ t.deepCollectInBody {
        case scope: Scope with Positioned =>
          scope.pos match {
            case p: AbstractSourcePosition =>
              scope.scopedDecls.map { local_decl => Definition(local_decl.name, "Local", local_decl.pos, Some(p)) }
            case _ => Seq()
          }
      }.flatten

    case t: Function =>
      (Definition(t.name, "Function", t.pos) +: (t.pos match {
        case p: AbstractSourcePosition =>
          t.formalArgs.map { arg => Definition(arg.name, "Argument", arg.pos, Some(p)) }
        case _ => Seq()
      })) ++ (t.body match {
        case Some(exp) =>
          exp.deepCollect {
            case scope:Scope with Positioned =>
              scope.pos match {
                case p: AbstractSourcePosition =>
                  scope.scopedDecls.map { local_decl => Definition(local_decl.name, "Local", local_decl.pos, Some(p)) }
                case _ => Seq()
              }
          } flatten
        case _ => Seq()
      })

    case t: Predicate =>
      (Definition(t.name, "Predicate", t.pos) +: (t.pos match {
        case p: AbstractSourcePosition =>
          t.formalArgs.map { arg => Definition(arg.name, "Argument", arg.pos, Some(p)) }
        case _ => Seq()
      })) ++ (t.body match {
        case Some(exp) =>
          exp.deepCollect {
            case scope:Scope with Positioned =>
              scope.pos match {
                case p: AbstractSourcePosition =>
                  scope.scopedDecls.map { local_decl => Definition(local_decl.name, "Local", local_decl.pos, Some(p)) }
                case _ => Seq()
              }
          } flatten
        case _ => Seq()
      })

    case t: Domain =>
      (Definition(t.name, "Domain", t.pos) +: (t.pos match {
        case p: AbstractSourcePosition =>
          t.functions.flatMap { func =>
            Definition(func.name, "Function", func.pos, Some(p)) +: (func.pos match {
              case func_p: AbstractSourcePosition =>
                func.formalArgs.map { arg => Definition(arg.name, "Argument", arg.pos, Some(func_p)) }
              case _ => Seq()
            })
          } ++ t.axioms.flatMap { ax =>
            Definition(ax.name, "Axiom", ax.pos, Some(p)) +: (ax.pos match {
              case ax_p: AbstractSourcePosition =>
                ax.exp.deepCollect {
                  case scope:Scope with Positioned =>
                    scope.pos match {
                      case p: AbstractSourcePosition =>
                        scope.scopedDecls.map { local_decl => Definition(local_decl.name, "Local", local_decl.pos, Some(p)) }
                      case _ => Seq()
                    }
                } flatten
              case _ => Seq()
            }) }
        case _ => Seq()
      })) ++ Seq()

    case t: Field =>
      Seq(Definition(t.name, "Field", t.pos))

  } flatten) toList

  private def countInstances(p: Program): Map[String, Int] = p.members.groupBy({
      case m: Method => "method"
      case fu: Function => "function"
      case p: Predicate => "predicate"
      case d: Domain => "domain"
      case fi: Field => "field"
      case _ => "other"
    }).mapValues(_.size)

  def execute(args: Seq[String]) {
    _frontend.setStartTime()

    // create the verifier
    _frontend.setVerifier( _frontend.createVerifier(args.mkString(" ")) )

    if (!_frontend.prepare(args)) return

    // initialize the translator
    _frontend.init( _frontend.verifier )

    // set the file we want to verify
    _frontend.reset( Paths.get(_frontend.config.file()) )

    // run the parser, typechecker, and verifier
    _frontend.parse()
    _frontend.typecheck()
    _frontend.translate()

    if (_frontend.errors.nonEmpty) {
      _frontend.setState( TranslatorState.Verified )

    } else {
      val prog: Program = _frontend.program.get
      val stats = countInstances(prog)

      _frontend.reporter.report(new ProgramOutlineReport(prog.members.toList))
      _frontend.reporter.report(new StatisticsReport(
        stats.getOrElse("method", 0),
        stats.getOrElse("function", 0),
        stats.getOrElse("predicate", 0),
        stats.getOrElse("domain", 0),
        stats.getOrElse("field", 0)
      ))
      _frontend.reporter.report(new ProgramDefinitionsReport(collectDefinitions(prog)))

      if (_frontend.config.disableCaching()) {
        _frontend.doVerify()
      } else {
        println("start cached verification")
        doVerifyCached()
      }
    }

    _frontend.verifier.stop()

    // finish by reporting the overall outcome

    _frontend.result match {
      case Success =>
        //printSuccess();
        _frontend.reporter.report(OverallSuccessMessage(_frontend.getVerifierName, System.currentTimeMillis() - _frontend.startTime))
      case f@Failure(_) =>
        //printErrors(errors: _*);
        _frontend.reporter.report(OverallFailureMessage(_frontend.getVerifierName, System.currentTimeMillis() - _frontend.startTime, f))
    }
  }

  private def getMethodSpecificErrors(m: Method, errors: Seq[AbstractError]): List[AbstractVerificationError] = {
    //The position of the error is used to determine to which Method it belongs.
    val methodStart = m.pos.asInstanceOf[SourcePosition].start.line
    val methodEnd = m.pos.asInstanceOf[SourcePosition].end.get.line
    val result = scala.collection.mutable.ListBuffer[AbstractVerificationError]()

    errors.foreach {
      case e: AbstractVerificationError =>
        e.pos match {
          case pos: HasLineColumn =>
            val errorPos = pos.line
            if (errorPos >= methodStart && errorPos <= methodEnd) result += e
          case _ =>
            throw new Exception("Error determining method specific errors for the cache: The reported errors should have a location")
        }
      case e =>
        throw new Exception("Error with unexpected type found: " + e)
    }
    result.toList
  }

  private def removeBody(m: Method): Method =
    m.copy(body = None)(m.pos, m.info, m.errT)

  def doVerifyCached(): Unit = {

    // The entityHashes of the new AST are evaluated lazily.

    val (methodsToVerify, _, cachedErrors) = consultCache()

    val real_program = _frontend.program.get
    val prog: Program = Program(real_program.domains, real_program.fields, real_program.functions, real_program.predicates,
      methodsToVerify) (real_program.pos, real_program.info, real_program.errT)
    val file: String = _frontend.config.file()

    _frontend.setVerificationResult( _frontend.mapVerificationResult(_frontend.verifier.verify(prog)) )

    _frontend.setState( TranslatorState.Verified )

    //update cache
    methodsToVerify.foreach(m => {
      _frontend.getVerificationResult.get match {
        case Failure(errors) =>
          val errorsToCache = getMethodSpecificErrors(m, errors)
          ViperCache.update(backendName, file, prog, m, errorsToCache)
          _frontend.logger.trace("Store in cache " + m.name + (if (errorsToCache.nonEmpty) ": Error" else ": Success"))
        case Success =>
          _frontend.logger.trace("Store in cache " + m.name + ": Success")
          ViperCache.update(backendName, file, prog, m, Nil)
      }
    })

    //combine errors:
    if (cachedErrors.nonEmpty) {
      _frontend.getVerificationResult.get match {
        case Failure(errorList) =>
          _frontend.setVerificationResult(Failure(errorList ++ cachedErrors))
        case Success =>
          _frontend.setVerificationResult(Failure(cachedErrors))
      }
    }
  }

  def backendName: String = _frontend.verifier.getClass.getName

  def consultCache(): (List[Method], List[Method], List[VerificationError]) = {
    val errors: collection.mutable.ListBuffer[VerificationError] = ListBuffer()
    val methodsToVerify: collection.mutable.ListBuffer[Method] = ListBuffer()
    val methodsToCache: collection.mutable.ListBuffer[Method] = ListBuffer()

    val file: String = _frontend.config.file()

    //read errors from cache
    val prog: Program = _frontend.program.get
    prog.methods.foreach((m: Method) => {
      ViperCache.get(backendName, file, m) match {
        case None =>
          methodsToVerify += m
        case Some(cacheEntry) =>
          if (prog.dependencyHashMap(m) != cacheEntry.dependencyHash) {
            //even if the method itself did not change, a re-verification is required if it's dependencies changed
            methodsToVerify += m
          } else {
            try {
              val cachedErrors = updateErrorLocation(m, cacheEntry)
              errors ++= cachedErrors
              methodsToCache += removeBody(m)
            } catch {
              case e: Exception =>
                _frontend.logger.warn("The cache lookup failed:" + e)
                //Defaults to verifying the method in case the cache lookup fails.
                methodsToVerify += m
            }
          }
      }
    })
    (methodsToVerify.toList, methodsToCache.toList, errors.toList)
  }

  private def updateErrorLocation(m: Method, cacheEntry: CacheEntry): List[VerificationError] = {
    cacheEntry.errors.map(updateErrorLocation(m, _))
  }

  private def updateErrorLocation(m: Method, error: LocalizedError): VerificationError = {
    assert(error.error != null && error.accessPath != null && error.reasonAccessPath != null)

    //get the corresponding offending node in the new AST
    //TODO: are these casts ok?
    val offendingNode = ViperCache.getNode(m, error.accessPath, error.error.offendingNode).asInstanceOf[Option[errors.ErrorNode]]
    val reasonOffendingNode = ViperCache.getNode(m, error.reasonAccessPath, error.error.reason.offendingNode).asInstanceOf[Option[errors.ErrorNode]]

    if (offendingNode.isEmpty || reasonOffendingNode.isEmpty) {
      throw new Exception("Cache error: no corresponding node found for error: " + error.error.readableMessage())
    }

    //create a new VerificationError that only differs in the Position of the offending Node
    //the cast is fine, because the offending Nodes are supposed to be ErrorNodes
    val updatedOffendingNode = updatePosition(error.error.offendingNode, offendingNode.get.pos).asInstanceOf[errors.ErrorNode]
    val updatedReasonOffendingNode = updatePosition(error.error.reason.offendingNode, reasonOffendingNode.get.pos).asInstanceOf[errors.ErrorNode]
    //TODO: how to also update the position of error.error.reason.offendingNode?
    val updatedError = error.error.withNode(updatedOffendingNode).asInstanceOf[AbstractVerificationError]
    setCached(updatedError)
  }

  def setCached(error: AbstractVerificationError): AbstractVerificationError = {
    error match {
      case e: Internal => e.copy(cached = true)
      case e: AssignmentFailed => e.copy(cached = true)
      case e: CallFailed => e.copy(cached = true)
      case e: ContractNotWellformed => e.copy(cached = true)
      case e: PreconditionInCallFalse => e.copy(cached = true)
      case e: PreconditionInAppFalse => e.copy(cached = true)
      case e: ExhaleFailed => e.copy(cached = true)
      case e: InhaleFailed => e.copy(cached = true)
      case e: IfFailed => e.copy(cached = true)
      case e: WhileFailed => e.copy(cached = true)
      case e: AssertFailed => e.copy(cached = true)
      case e: TerminationFailed => e.copy(cached = true)
      case e: PostconditionViolated => e.copy(cached = true)
      case e: FoldFailed => e.copy(cached = true)
      case e: UnfoldFailed => e.copy(cached = true)
      case e: PackageFailed => e.copy(cached = true)
      case e: ApplyFailed => e.copy(cached = true)
      case e: LoopInvariantNotPreserved => e.copy(cached = true)
      case e: LoopInvariantNotEstablished => e.copy(cached = true)
      case e: FunctionNotWellformed => e.copy(cached = true)
      case e: PredicateNotWellformed => e.copy(cached = true)
      case e: MagicWandNotWellformed => e.copy(cached = true)
      case e: LetWandFailed => e.copy(cached = true)
      case e: HeuristicsFailed => e.copy(cached = true)
      case e: VerificationErrorWithCounterexample => e.copy(cached = true)
      case e: AbstractVerificationError =>
        _frontend.logger.warn("Setting a verification error to cached was not possible for " + e + ". Make sure to handle this types of errors")
        e
    }
  }

  def updatePosition(n: Node, pos: Position): Node = {
    n match {
      case t: Trigger => t.copy()(pos, t.info, t.errT)
      case t: Program => t.copy()(pos, t.info, t.errT)

      //Members
      case t: Field => t.copy()(pos, t.info, t.errT)
      case t: Function => t.copy()(pos, t.info, t.errT)
      case t: Method => t.copy()(pos, t.info, t.errT)
      case t: Predicate => t.copy()(pos, t.info, t.errT)
      case t: Domain => t.copy()(pos, t.info, t.errT)

      //DomainMembers
      case t: DomainAxiom => t.copy()(pos, t.info, t.domainName, t.errT)
      case t: DomainFunc => t.copy()(pos, t.info, t.domainName, t.errT)

      //Statements
      case t: NewStmt => t.copy()(pos, t.info, t.errT)
      case t: LocalVarAssign => t.copy()(pos, t.info, t.errT)
      case t: FieldAssign => t.copy()(pos, t.info, t.errT)
      case t: Fold => t.copy()(pos, t.info, t.errT)
      case t: Unfold => t.copy()(pos, t.info, t.errT)
      case t: Package => t.copy()(pos, t.info, t.errT)
      case t: Apply => t.copy()(pos, t.info, t.errT)
      case t: Inhale => t.copy()(pos, t.info, t.errT)
      case t: Exhale => t.copy()(pos, t.info, t.errT)
      case t: Assert => t.copy()(pos, t.info, t.errT)
      case t: MethodCall => t.copy()(pos, t.info, t.errT)
      case t: Seqn => t.copy()(pos, t.info, t.errT)
      case t: While => t.copy()(pos, t.info, t.errT)
      case t: If => t.copy()(pos, t.info, t.errT)
      case t: Label => t.copy()(pos, t.info, t.errT)
      case t: Goto => t.copy()(pos, t.info, t.errT)
      case t: Fresh => t.copy()(pos, t.info, t.errT)
      case t: Constraining => t.copy()(pos, t.info, t.errT)
      case t: LocalVarDeclStmt => t.copy()(pos, t.info, t.errT)

      case t: LocalVarDecl => t.copy()(pos, t.info, t.errT)

      //Expressions
      case t: FalseLit => t.copy()(pos, t.info, t.errT)
      case t: NullLit => t.copy()(pos, t.info, t.errT)
      case t: TrueLit => t.copy()(pos, t.info, t.errT)
      case t: IntLit => t.copy()(pos, t.info, t.errT)
      case t: LocalVar => t.copy()(t.typ, pos, t.info, t.errT)
      case t: viper.silver.ast.Result => t.copy()(t.typ, pos, t.info, t.errT)
      case t: FieldAccess => t.copy()(pos, t.info, t.errT)
      case t: PredicateAccess => t.copy()(pos, t.info, t.errT)
      case t: Unfolding => t.copy()(pos, t.info, t.errT)
      case t: Applying => t.copy()(pos, t.info, t.errT)
      case t: CondExp => t.copy()(pos, t.info, t.errT)
      case t: Let => t.copy()(pos, t.info, t.errT)
      case t: Exists => t.copy()(pos, t.info, t.errT)
      case t: Forall => t.copy()(pos, t.info, t.errT)
      case t: ForPerm => t.copy()(pos, t.info, t.errT)
      case t: InhaleExhaleExp => t.copy()(pos, t.info, t.errT)
      case t: WildcardPerm => t.copy()(pos, t.info, t.errT)
      case t: FullPerm => t.copy()(pos, t.info, t.errT)
      case t: NoPerm => t.copy()(pos, t.info, t.errT)
      case t: EpsilonPerm => t.copy()(pos, t.info, t.errT)
      case t: CurrentPerm => t.copy()(pos, t.info, t.errT)
      case t: FieldAccessPredicate => t.copy()(pos, t.info, t.errT)
      case t: PredicateAccessPredicate => t.copy()(pos, t.info, t.errT)

      //Binary operators
      case t: Add => t.copy()(pos, t.info, t.errT)
      case t: Sub => t.copy()(pos, t.info, t.errT)
      case t: Mul => t.copy()(pos, t.info, t.errT)
      case t: Div => t.copy()(pos, t.info, t.errT)
      case t: Mod => t.copy()(pos, t.info, t.errT)
      case t: LtCmp => t.copy()(pos, t.info, t.errT)
      case t: LeCmp => t.copy()(pos, t.info, t.errT)
      case t: GtCmp => t.copy()(pos, t.info, t.errT)
      case t: GeCmp => t.copy()(pos, t.info, t.errT)
      case t: EqCmp => t.copy()(pos, t.info, t.errT)
      case t: NeCmp => t.copy()(pos, t.info, t.errT)
      case t: Or => t.copy()(pos, t.info, t.errT)
      case t: And => t.copy()(pos, t.info, t.errT)
      case t: Implies => t.copy()(pos, t.info, t.errT)
      case t: MagicWand => t.copy()(pos, t.info, t.errT)
      case t: FractionalPerm => t.copy()(pos, t.info, t.errT)
      case t: PermDiv => t.copy()(pos, t.info, t.errT)
      case t: PermAdd => t.copy()(pos, t.info, t.errT)
      case t: PermSub => t.copy()(pos, t.info, t.errT)
      case t: PermMul => t.copy()(pos, t.info, t.errT)
      case t: IntPermMul => t.copy()(pos, t.info, t.errT)
      case t: PermLtCmp => t.copy()(pos, t.info, t.errT)
      case t: PermLeCmp => t.copy()(pos, t.info, t.errT)
      case t: PermGtCmp => t.copy()(pos, t.info, t.errT)
      case t: PermGeCmp => t.copy()(pos, t.info, t.errT)
      case t: AnySetUnion => t.copy()(pos, t.info, t.errT)
      case t: AnySetIntersection => t.copy()(pos, t.info, t.errT)
      case t: AnySetSubset => t.copy()(pos, t.info, t.errT)
      case t: AnySetMinus => t.copy()(pos, t.info, t.errT)
      case t: AnySetContains => t.copy()(pos, t.info, t.errT)

      //Unary operators
      case t: Minus => t.copy()(pos, t.info, t.errT)
      case t: Not => t.copy()(pos, t.info, t.errT)
      case t: PermMinus => t.copy()(pos, t.info, t.errT)
      case t: Old => t.copy()(pos, t.info, t.errT)
      case t: LabelledOld => t.copy()(pos, t.info, t.errT)
      case t: AnySetCardinality => t.copy()(pos, t.info, t.errT)
      case t: FuncApp => t.copy()(pos, t.info, t.typ, t.formalArgs, t.errT)
      case t: DomainFuncApp => t.copy()(pos, t.info, t.typ, t.formalArgs, t.domainName, t.errT)
      case t: EmptySeq => t.copy()(pos, t.info, t.errT)
      case t: ExplicitSeq => t.copy()(pos, t.info, t.errT)
      case t: RangeSeq => t.copy()(pos, t.info, t.errT)
      case t: SeqAppend => t.copy()(pos, t.info, t.errT)
      case t: SeqIndex => t.copy()(pos, t.info, t.errT)
      case t: SeqTake => t.copy()(pos, t.info, t.errT)
      case t: SeqDrop => t.copy()(pos, t.info, t.errT)
      case t: SeqContains => t.copy()(pos, t.info, t.errT)
      case t: SeqUpdate => t.copy()(pos, t.info, t.errT)
      case t: SeqLength => t.copy()(pos, t.info, t.errT)

      //others
      case t: EmptySet => t.copy()(pos, t.info, t.errT)
      case t: ExplicitSet => t.copy()(pos, t.info, t.errT)
      case t: EmptyMultiset => t.copy()(pos, t.info, t.errT)
      case t: ExplicitMultiset => t.copy()(pos, t.info, t.errT)
      case t =>
        _frontend.logger.warn("The location was not updated for the node " + t + ". Make sure to handle this type of node")
        t
    }
  }

  def stop = _frontend.verifier.stop()

  /** The operations below are borrowed from whatever implementation of SilFrontend is the _frontend field. */
  /*
  val logger: Logger = _frontend.logger
  def createVerifier(fullCmd: String): Verifier = _frontend.createVerifier(fullCmd)
  def configureVerifier(args: Seq[String]): SilFrontendConfig = _frontend.configureVerifier(args)
  def verifier: Verifier = _frontend.verifier
  def ver: Verifier = _frontend.ver
  def config: SilFrontendConfig = _frontend.config
  def program: Option[Program] = _frontend.program // this one comes all the way from DefaultFrontend
  */

  /** The operations below are not supposed to be used by instances of ViperFrontend. */
  /*
  protected var _ver: Verifier =


  def config =
  protected var _plugins: SilverPluginManager =
  protected var _config: SilFrontendConfig =
  def plugins =
  protected var _startTime: Long =
  def startTime =
  def resetMessages() =
  def setVerifier(verifier:Verifier): Unit =
  def prepare(args: Seq[String]): Boolean =
  def execute(args: Seq[String])
  override def reset(input: Path): Unit =
  def setStartTime(): Unit =
  protected def getVerifierName: String =

  def finish(): Unit =
  protected def parseCommandLine(args: Seq[String]) =
  protected def printFallbackHeader() =
  protected def printHeader() =
  protected def printFinishHeader() =

  protected def printFinishHeaderWithTime() = frontend.printFinishHeaderWithTime()

  override def printErrors(errors: AbstractError*) = frontend.printErrors()
  override def printSuccess() = frontend.printSuccess()
  override def doParse(input: String): Result[ParserResult] = frontend.doParse(input)
  override def doTypecheck(input: ParserResult): Result[TypecheckerResult] = frontend.doTypecheck(input)
  override def doTranslate(input: TypecheckerResult): Result[Program] = frontend.doTranslate(input)

  override def mapVerificationResult(in: VerificationResult): VerificationResult = frontend.mapVerificationResult(in)
  */
}
