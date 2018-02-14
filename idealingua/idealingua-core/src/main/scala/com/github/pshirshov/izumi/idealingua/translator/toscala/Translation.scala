package com.github.pshirshov.izumi.idealingua.translator.toscala

import com.github.pshirshov.izumi.idealingua
import com.github.pshirshov.izumi.idealingua.model.common._
import com.github.pshirshov.izumi.idealingua.model.exceptions.IDLException
import com.github.pshirshov.izumi.idealingua.model.finaldef.DefMethod.RPCMethod
import com.github.pshirshov.izumi.idealingua.model.finaldef.FinalDefinition._
import com.github.pshirshov.izumi.idealingua.model.finaldef.{DomainDefinition, FinalDefinition, Service, Typespace}
import com.github.pshirshov.izumi.idealingua.model.output.{Module, ModuleId}
import com.github.pshirshov.izumi.idealingua.model.runtime._

import scala.collection.mutable
import scala.meta._


class Translation(domain: DomainDefinition) {
  protected val typespace = new Typespace(domain)
  protected val conv = new ScalaTypeConverter(typespace)

  final val idtInit = conv.initFor[IDLIdentifier]
  final val idtGenerated = conv.initFor[IDLGenerated]
  final val idtService = conv.initFor[IDLService]
  final val inputInit = conv.initFor[IDLInput]
  final val outputInit = conv.initFor[IDLOutput]
  final val domainCompanionInit = conv.initFor[IDLDomainCompanion]
  final val typeCompanionInit = conv.initFor[IDLTypeCompanion]
  final val enumInit = conv.initFor[IDLEnum]
  final val enumElInit = conv.initFor[IDLEnumElement]
  final val serviceCompanionInit = conv.initFor[IDLServiceCompanion]

  final val domainCompanionType = JavaType(Seq("izumi", "idealingua", "domains"), domain.id.capitalize)
  final val domainCompanion = Term.Name(domainCompanionType.name)

  protected val packageObjects: mutable.HashMap[ModuleId, mutable.ArrayBuffer[Defn]] = mutable.HashMap[ModuleId, mutable.ArrayBuffer[Defn]]()

  def translate(): Seq[Module] = {
    domain
      .types
      .flatMap(translateDef) ++
      packageObjects.map {
        case (id, content) =>
          // TODO: dirty!
          val pkgName = id.name.split('.').head

          val code =
            s"""
               |package object $pkgName {
               |${content.map(_.toString()).mkString("\n\n")}
               |}
           """.stripMargin
          Module(id, withPackage(id.path.init, code))
      } ++
      domain.services.flatMap(translateService) ++
      translateDomain()
  }

  protected def translateDomain(): Seq[Module] = {
    toSource(domainCompanionType.pkg, ModuleId(domainCompanionType.pkg, domain.id), Seq(
      q"""object $domainCompanion extends $domainCompanionInit {
                override final lazy val domain: ${conv.toSelect(JavaType.get[DomainDefinition])} = {
                ${SchemaSerializer.toAst(domain)}
              }
             }"""
    ))

  }

  protected def translateService(definition: Service): Seq[Module] = {
    toSource(definition.id.pkg, toModuleId(definition.id), renderService(definition))
  }


  protected def translateDef(definition: FinalDefinition): Seq[Module] = {
    val defns = definition match {
      case a: Alias =>
        packageObjects.getOrElseUpdate(toModuleId(a), mutable.ArrayBuffer()) ++= renderAlias(a)
        Seq()

      case i: Enumeration =>
        renderEnumeration(i)

      case i: Identifier =>
        renderIdentifier(i)

      case i: Interface =>
        renderInterface(i)

      case d: DTO =>
        renderDto(d)
    }
    if (defns.nonEmpty) {
      toSource(definition.id.pkg, toModuleId(definition), defns)
    } else {
      Seq.empty
    }
  }

  def renderEnumeration(i: Enumeration): Seq[Defn] = {
    val typeName = i.id.name
    val tpet = Type.Name(typeName)
    val tpe = Term.Name(typeName)
    val init = Init(tpet, Name.Anonymous(), List.empty)

    val members = i.members.map {
      m =>
        val mt = Term.Name(m)
        q"""case object $mt extends $init {
              override def toString: String = ${Lit.String(m)}
            }"""
    }

    Seq(
      q""" sealed trait $tpet extends $enumElInit {} """
      , q"""object $tpe extends $enumInit {
            type Element = $tpet

            override def all: Seq[$tpet] = Seq(..${members.map(_.name)})
            
            ..$members

           }"""
    )
  }

  protected def renderAlias(i: Alias): Seq[Defn] = {
    Seq(Defn.Type(List.empty, Type.Name(i.id.name), List.empty, conv.toScalaType(i.target)))
  }

  protected def renderIdentifier(i: Identifier): Seq[Defn] = {
    val fields = typespace.fetchFields(i)
    val decls = toScala(fields).map {
      f =>
        Term.Param(List.empty, f.name, Some(f.declType), None)
    }

    val superClasses = if (fields.lengthCompare(1) == 0) {
      List(
        Init(Type.Name("AnyVal"), Name.Anonymous(), List.empty)
        , idtGenerated
        , idtInit
      )
    } else {
      List(idtGenerated, idtInit)
    }

    //val exploded = explode(i)
    // TODO: contradictions

    val typeName = i.id.name

    val idt = conv.toSelectTerm(JavaType.get[IDLIdentifier])
    val interp = Term.Interpolate(Term.Name("s"), List(Lit.String(typeName + "#"), Lit.String("")), List(Term.Name("suffix")))

    val tpet = Type.Name(typeName)
    val tpe = Term.Name(typeName)

    Seq(
      q"""case class $tpet (..$decls) extends ..$superClasses {
            override def toString: String = {
              val suffix = this.productIterator.map(part => $idt.escape(part.toString)).mkString(":")
              $interp
            }

            override def companion: $tpe.type = $tpe
         }"""
      , makeTypeCompanion(i, typeName)
    )
  }

  private def makeTypeCompanion(i: FinalDefinition, typeName: TypeName) = {
    q"""object ${Term.Name(typeName)} extends $typeCompanionInit {
             override final lazy val definition: ${conv.toSelect(JavaType.get[FinalDefinition])} = {
              ${SchemaSerializer.toAst(i)}
             }

             override final def domain: ${conv.toSelect(JavaType.get[IDLDomainCompanion])} = {
              ${conv.toSelectTerm(domainCompanionType)}
             }
         }"""
  }

  protected def renderInterface(i: Interface): Seq[Defn] = {
    val fields = typespace.fetchFields(i)
    val scalaFields: Seq[ScalaField] = toScala(fields)

    val typeName = i.id.name

    // TODO: contradictions
    val decls = scalaFields.toList.map {
      f =>
        Decl.Def(List.empty, f.name, List.empty, List.empty, f.declType)
    }

    val scalaIfaces = i.interfaces.map(typespace.apply).toList
    val ifDecls = idtGenerated +: scalaIfaces.map {
      iface =>
        Init(conv.toScalaType(iface.id), Name.Anonymous(), List.empty)
    }

    val tpe = Term.Name(typeName)
    Seq(
      q"""trait ${Type.Name(typeName)} extends ..$ifDecls {
          override def companion: $tpe.type = $tpe

          ..$decls
          }

       """
      , makeTypeCompanion(i, typeName)
    )
  }

  protected def renderService(i: Service): Seq[Defn] = {
    val typeName = i.id.name

    case class ServiceMethodProduct(defn: Stat, routingClause: Case, types: Seq[Defn])

    val serviceInputBase = Type.Name(s"In${typeName.capitalize}")
    val serviceOutputBase = Type.Name(s"Out${typeName.capitalize}")

    val decls = i.methods.toList.map {
      case method: RPCMethod =>
        val inName = s"In${method.name.capitalize}"
        val outName = s"Out${method.name.capitalize}"

        val inputComposite = renderComposite(inName, method.signature.input, List(Init(serviceInputBase, Name.Anonymous(), List.empty)))
        val outputComposite = renderComposite(outName, method.signature.output, List(Init(serviceOutputBase, Name.Anonymous(), List.empty)))

        val inputType = Type.Name(inName)
        val outputType = Type.Name(outName)

        ServiceMethodProduct(
          q"def ${Term.Name(method.name)}(input: $inputType): $outputType"
          , Case(
            Pat.Typed(Pat.Var(Term.Name("value")), inputType)
            , None
            , q"service.${Term.Name(method.name)}(value)"
          )
          , inputComposite ++ outputComposite
        )
    }

    val forwarder = Term.Match(Term.Name("input"), decls.map(_.routingClause))
    val tpe = Type.Name(typeName)
    val tpet = Term.Name(typeName)

    val transportDecls = List(
      q"override def process(input: ${idtGenerated.tpe}): ${idtGenerated.tpe} = $forwarder"
    )

    Seq(
      q"""trait $tpe extends $idtService {
          import $tpet._

          override def companion: $tpet.type = $tpet

          ..${decls.map(_.defn)}
         }"""
      ,
      q"""class ${Type.Name(typeName + "AbstractTransport")}
            (
              override val service: $tpe
            ) extends ${conv.init[AbstractTransport[_]](List(tpe))} {
            import $tpet._

            ..$transportDecls
           }"""
      ,
      q"""object $tpet extends $serviceCompanionInit {
            trait $serviceInputBase extends $inputInit {}
            trait $serviceOutputBase extends $outputInit {}

            override type InputType = $serviceInputBase
            override type OutputType = $serviceOutputBase

            override def inputTag: scala.reflect.ClassTag[$serviceInputBase] = scala.reflect.classTag[$serviceInputBase]
            override def outputTag: scala.reflect.ClassTag[$serviceOutputBase] = scala.reflect.classTag[$serviceOutputBase]


            override final lazy val schema: ${conv.toSelect(JavaType.get[Service])} = {
              ${SchemaSerializer.toAst(i)}
            }
            override final def domain: ${conv.toSelect(JavaType.get[IDLDomainCompanion])} = {
              ${conv.toSelectTerm(domainCompanionType)}
            }

            ..${decls.flatMap(_.types)}
           }"""
    )
  }

  protected def renderDto(i: DTO): Seq[Defn] = {
    val typeName = i.id.name
    val interfaces = i.interfaces
    renderComposite(typeName, interfaces, List.empty) :+ makeTypeCompanion(i, typeName)
  }

  private def renderComposite(typeName: TypeName, interfaces: Composite, bases: List[Init]) = {
    val fields = typespace.fetchFields(interfaces)
    val scalaIfaces = interfaces.map(typespace.apply).toList
    val scalaFields: Seq[ScalaField] = toScala(fields)
    val decls = scalaFields.toList.map {
      f =>
        Term.Param(List.empty, f.name, Some(f.declType), None)
    }

    val ifDecls = scalaIfaces.map {
      iface =>
        Init(conv.toScalaType(iface.id), Name.Anonymous(), List.empty)
    }

    // TODO: contradictions

    val suprerClasses = bases ++ (if (fields.lengthCompare(1) == 0) {
      ifDecls :+ Init(Type.Name("AnyVal"), Name.Anonymous(), List.empty)
    } else {
      ifDecls
    })


    Seq(
      q"case class ${Type.Name(typeName)}(..$decls) extends ..$suprerClasses"
    )
  }

  private def toModuleId(defn: FinalDefinition): ModuleId = {
    defn match {
      case i: Alias =>
        ModuleId(i.id.pkg, s"${i.id.pkg.last}.scala")

      case other =>
        val id = other.id
        toModuleId(id)
    }
  }

  private def toModuleId(id: TypeId): ModuleId = {
    ModuleId(id.pkg, s"${id.name}.scala")
  }

  private def toSource(pkg: Package, moduleId: ModuleId, traitDef: Seq[Defn]) = {
    val code = traitDef.map(_.toString()).mkString("\n\n")
    val content: String = withPackage(pkg, code)
    Seq(Module(moduleId, content))
  }

  private def withPackage(pkg: idealingua.model.common.Package, code: String) = {
    val content = if (pkg.isEmpty) {
      code
    } else {
      s"""package ${pkg.mkString(".")}
         |
         |$code
       """.stripMargin
    }
    content
  }

  protected def toScala(fields: Seq[Field]): List[ScalaField] = {
    val conflictingFields = fields.groupBy(_.name).filter(_._2.lengthCompare(1) > 0)
    if (conflictingFields.nonEmpty) {
      throw new IDLException(s"Conflicting fields: $conflictingFields")
    }

    fields.map(toScala).toList
  }


  protected def toScala(field: Field): ScalaField = {
    ScalaField(Term.Name(field.name), conv.toScalaType(field.typeId))
  }


}

