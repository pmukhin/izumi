package izumi.distage.provisioning.strategies

import izumi.distage.model.exceptions.MissingInstanceException
import izumi.distage.model.plan.ExecutableOp.ImportDependency
import izumi.distage.model.provisioning.strategies.{FactoryExecutor, ImportStrategy}
import izumi.distage.model.provisioning.{NewObjectOp, ProvisioningKeyProvider}
import izumi.distage.model.reflection.universe.RuntimeDIUniverse
import izumi.fundamentals.platform.language.Quirks

class ImportStrategyDefaultImpl extends ImportStrategy {
  override def importDependency(context: ProvisioningKeyProvider, op: ImportDependency): Seq[NewObjectOp] = {
    context.importKey(op.target) match {
      case Some(v) =>
        Seq(NewObjectOp.NewImport(op.target, v))
      // FIXME: TODO: support FactoryStrategyMacro [remove]
      case _ if op.target == RuntimeDIUniverse.DIKey.get[FactoryExecutor] =>
        Seq.empty
      case _ =>
        throw new MissingInstanceException(MissingInstanceException.format(op.target, op.references), op.target)
    }
  }
}

class ImportStrategyFailingImpl extends ImportStrategy {
  override def importDependency(context: ProvisioningKeyProvider, op: ImportDependency): Seq[NewObjectOp] = {
    Quirks.discard(context)

    import op._
    throw new MissingInstanceException(s"Imports are disabled and instance is not available in the object graph: $target. required by refs: $references", target)
  }
}

