package com.ensime.server.model

import scala.tools.nsc.interactive.{Global, CompilerControl}
import scala.tools.nsc.util.{SourceFile, Position, OffsetPosition}
import scala.tools.nsc.util.NoPosition
import scala.tools.nsc.symtab.Types
import scala.tools.nsc.symtab.Symbols
import com.ensime.util.{SExp, SExpable}
import com.ensime.util.SExp._
import scala.collection.mutable.{ HashMap, HashEntry, HashSet }


object SExpConversion{

  implicit def posToSExp(pos:Position):SExp = {
    if(pos.isDefined){
      SExp.propList(
	(":file", pos.source.path),
	(":offset", pos.point + 1) // <- Emacs point starts at 1
      )
    }
    else{
      'nil
    }
  }

}



import SExpConversion._


abstract class EntityInfo(val name:String, val members:Iterable[EntityInfo]) extends SExpable{}

class PackageInfo(override val name:String, val fullname:String, override val members:Iterable[EntityInfo]) extends EntityInfo(name, members){
  def toSExp():SExp = {
    SExp.propList(
      (":name", name),
      (":info-type", 'package),
      (":full-name", fullname),
      (":members", SExp(members.map{_.toSExp}))
    )
  }
}

abstract trait LooksLikeType{
  def name:String
  def id:Int 
  def declaredAs:scala.Symbol
  def fullName:String 
  def pos:Position
}


class SymbolInfo(
  val name:String, 
  val declPos:Position, 
  val tpe:TypeInfo,
  val isCallable:Boolean) extends SExpable {

  def toSExp():SExp = {
    SExp.propList(
      (":name", name),
      (":type", tpe.toSExp),
      (":decl-pos", declPos),
      (":is-callable", isCallable)
    )
  }
}


class SymbolInfoLight(
  val name:String, 
  val tpeName:String,
  val tpeId:Int,
  val isCallable:Boolean) extends SExpable {

  def toSExp():SExp = {
    SExp.propList(
      (":name", name),
      (":type-name", tpeName),
      (":type-id", tpeId),
      (":is-callable", isCallable)
    )
  }
}


class NamedTypeMemberInfo(override val name:String, val tpe:TypeInfo, val pos:Position) extends EntityInfo(name, List()) with SExpable {
  def toSExp():SExp = {
    SExp.propList(
      (":name", name),
      (":type", tpe.toSExp),
      (":pos", pos)
    )
  }
}


class NamedTypeMemberInfoLight(override val name:String, tpeName:String, tpeId:Int, isCallable:Boolean) extends EntityInfo(name, List()){
  def toSExp():SExp = {
    SExp.propList(
      (":name", name),
      (":type-name", tpeName),
      (":type-id", tpeId),
      (":is-callable", isCallable)
    )
  }
}


class TypeInfo(
  name:String, 
  val id:Int, 
  val declaredAs:scala.Symbol,
  val fullName:String, 
  val args:Iterable[TypeInfo], 
  members:Iterable[EntityInfo],
  val pos:Position) extends EntityInfo(name, members) with LooksLikeType{

  implicit def toSExp():SExp = {
    SExp.propList(
      (":name", name),
      (":type-id", id),
      (":full-name", fullName),
      (":declared-as", declaredAs),
      (":type-args", SExp(args.map(_.toSExp))),
      (":members", SExp(members.map(_.toSExp))),
      (":pos", pos)
    )
  }
}

class ArrowTypeInfo(
  override val name:String, 
  override val id:Int, 
  val resultType:TypeInfo,
  val paramTypes:Iterable[TypeInfo]) extends TypeInfo(name, id, 'nil, name, List(), List(), NoPosition){

  override implicit def toSExp():SExp = {
    SExp.propList(
      (":name", name),
      (":type-id", id),
      (":arrow-type", true),
      (":result-type", resultType.toSExp),
      (":param-types", SExp(paramTypes.map(_.toSExp)))
    )
  }
}

class CallCompletionInfo(
  val resultType:TypeInfo, 
  val paramTypes:Iterable[TypeInfo],
  val paramNames:Iterable[String]) extends SExpable(){

  override implicit def toSExp():SExp = {
    SExp.propList(
      (":result-type", resultType.toSExp),
      (":param-types", SExp(paramTypes.map(_.toSExp))),
      (":param-names", SExp(paramNames.map(strToSExp(_))))
    )
  }
}

class InterfaceInfo(tpe:TypeInfo, viaView:Option[String]) extends SExpable{
  def toSExp():SExp = {
    SExp.propList(
      (":type", tpe.toSExp),
      (":via-view", viaView.map(strToSExp).getOrElse('nil))
    )
  }  
}

class TypeInspectInfo(tpe:TypeInfo, supers:Iterable[InterfaceInfo]) extends SExpable{
  def toSExp():SExp = {
    SExp.propList(
      (":type", tpe.toSExp),
      (":interfaces", SExp(supers.map{_.toSExp}))
    )
  }
}


trait ModelBuilders {  self: Global => 

  import self._
  import definitions.{ ObjectClass, ScalaObjectClass, RootPackage, EmptyPackage, NothingClass, AnyClass, AnyRefClass }

  private val typeCache = new HashMap[Int, Type]
  private val typeCacheReverse = new HashMap[Type, Int]
  
  def clearTypeCache(){
    typeCache.clear
    typeCacheReverse.clear
  }
  def typeById(id:Int):Option[Type] = { 
    typeCache.get(id)
  }
  def cacheType(tpe:Type):Int = {
    if(typeCacheReverse.contains(tpe)){
      typeCacheReverse(tpe)
    }
    else{
      val id = typeCache.size + 1
      typeCache(id) = tpe
      typeCacheReverse(tpe) = id
      id
    }
  }


  object Helpers{

    def isArrowType(tpe:Type) ={
      tpe match{
	case _:MethodType => true
	case _:PolyType => true
	case _ => false
      }
    }

    def isNoParamArrowType(tpe:Type) ={
      tpe match{
	case t:MethodType => t.params.isEmpty
	case t:PolyType => t.params.isEmpty
	case t:Type => false
      }
    }

    def typeOrArrowTypeResult(tpe:Type) ={
      tpe match{
	case t:MethodType => t.resultType
	case t:PolyType => t.resultType
	case t:Type => t
      }
    }

    def normalizeSym(aSym: Symbol): Symbol = aSym match {
      case null | EmptyPackage | NoSymbol                   => normalizeSym(RootPackage)
      case ScalaObjectClass | ObjectClass                   => normalizeSym(AnyRefClass)
      case _ if aSym.isModuleClass || aSym.isPackageObject  => normalizeSym(aSym.sourceModule)
      case _                                                => aSym
    }

  }


  object PackageInfo{

    import Helpers._

    def root: PackageInfo = fromSymbol(RootPackage)

    def fromPath(path:String): PackageInfo = {
      val pack = packageSymFromPath(path)
      pack match{
	case Some(packSym) => fromSymbol(packSym)
	case None => nullInfo
      }
    }

    private def packageSymFromPath(path:String):Option[Symbol] = {
      val pathSegs = path.split("\\.")
      val pack = pathSegs.foldLeft(RootPackage){ (packSym,seg) =>
	val member = packSym.info.members find { s =>
	  s.nameString == seg && s != EmptyPackage && s != RootPackage
	}
	member.getOrElse(packSym)
      }
      if(pack == RootPackage) None
      else Some(pack)
    }

    def nullInfo = {
      new PackageInfo("NA", "NA", List())
    }
    
    def fromSymbol(aSym: Symbol): PackageInfo = {
      val bSym = normalizeSym(aSym)
      val bSymFullName = bSym.fullName
      def makeMembers(symbols:Iterable[Symbol]) = {
	val validSyms = symbols.filter { s => 
	  s != EmptyPackage && s != RootPackage && s.owner.fullName == bSymFullName
	}
	val members = validSyms.flatMap(packageMemberFromSym).toList
	members.sortWith{(a,b) => a.name <= b.name}
      }

      val pack = if (bSym == RootPackage) {
	val memberSyms = (bSym.info.members ++ EmptyPackage.info.members)
	new PackageInfo(
	  "root",
	  "_root_",
	  makeMembers(memberSyms)
	)
      }
      else{
	val memberSyms = bSym.info.members
	new PackageInfo(
	  bSym.name.toString,
	  bSym.fullName,
	  makeMembers(memberSyms)
	)
      }
      pack
    }

    def packageMemberFromSym(aSym:Symbol): Option[EntityInfo] ={
      val bSym = normalizeSym(aSym)
      if (bSym == RootPackage){
	Some(root)
      }
      else if (bSym.isPackage){	
	Some(fromSymbol(bSym))
      }
      else if(!(bSym.nameString.contains("$"))){
	if(bSym.isClass || bSym.isTrait || 
	  bSym.isModuleClass || bSym.isPackageClass){
	  Some(TypeInfo(bSym.tpe))
	}
	else{
	  None
	}
      }
      else{
	None
      }
    }

  }



  object TypeInfo{

    import scala.tools.nsc.symtab.Flags._

    /* See source at root/scala/trunk/src/compiler/scala/tools/nsc/symtab/Symbols.scala  
    for details on various symbol predicates. */
    def declaredAs(sym:Symbol):scala.Symbol = {
      if(sym.isTrait)
      'trait
      else if(sym.isTrait && sym.hasFlag(JAVA))
      'interface
      else if(sym.isInterface)
      'interface
      else if(sym.isModule)
      'object
      else if(sym.isModuleClass)
      'object
      else if(sym.isClass)
      'class
      else if(sym.isPackageClass)
      'class
      else if(sym.isAbstractClass)
      'abstractclass
      else 'nil
    }

    def apply(tpe:Type, members:List[EntityInfo] = List()):TypeInfo = {
      tpe match{
	case tpe:MethodType => ArrowTypeInfo(tpe)
	case tpe:PolyType => ArrowTypeInfo(tpe)
	case tpe:Type =>
	{
	  val params = tpe.typeParams.map(_.toString)
	  val args = tpe.typeArgs.map(TypeInfo(_))
	  val typeSym = tpe.typeSymbol
	  new TypeInfo(
	    typeSym.name.toString,
	    cacheType(tpe), 
	    declaredAs(typeSym), 
	    typeSym.fullName, 
	    args,
	    members,
	    typeSym.pos
	  )
	}
	case _ => nullInfo
      }
    }

    def nullInfo() = {
      new TypeInfo("NA", -1, 'nil, "NA", List(), List(), NoPosition)
    }
  }


  object CallCompletionInfo{

    def apply(tpe:Type):CallCompletionInfo = {
      tpe match{
	case tpe:MethodType => apply(tpe.paramTypes, tpe.params, tpe.resultType)
	case tpe:PolyType => apply(tpe.paramTypes, tpe.params, tpe.resultType)
	case _ => nullInfo
      }
    }

    def apply(paramTypes:Iterable[Type], paramNames:Iterable[Symbol], resultType:Type):CallCompletionInfo = {
      new CallCompletionInfo(
	TypeInfo(resultType),
	paramTypes.map(t => TypeInfo(t)),
	paramNames.map(s => s.nameString)
      )
    }

    def nullInfo() = {
      new CallCompletionInfo(TypeInfo.nullInfo, List(), List())
    }
  }

  object SymbolInfo{

    def apply(sym:Symbol):SymbolInfo = {
      new SymbolInfo(
	sym.name.toString,
	sym.pos,
	TypeInfo(sym.tpe),
	Helpers.isArrowType(sym.tpe)
      )
    }

    def nullInfo() = {
      new SymbolInfo("NA", NoPosition, TypeInfo.nullInfo, false)
    }

  }

  object SymbolInfoLight{

    /** 
    *  Return symbol infos for any like-named constructors.
    */
    def constructorSynonyms(sym:Symbol):List[SymbolInfoLight] = {
      val members = if(sym.isClass || sym.isPackageClass) { 
	sym.tpe.members 
      } else if(sym.isModule || sym.isModuleClass){ 
	sym.companionClass.tpe.members
      } else {List()}

      members.flatMap{ member:Symbol =>
	if(member.isConstructor){
	  Some(SymbolInfoLight(sym, member.tpe))
	}
	else{None}
      }
    }
    
    /** 
    *  Return symbol infos for any like-named apply methods.
    */
    def applySynonyms(sym:Symbol):List[SymbolInfoLight] = {
      val members = if(sym.isModule || sym.isModuleClass) { 
	sym.tpe.members
      } else if(sym.isClass || sym.isPackageClass){ 
	sym.companionModule.tpe.members
      } else {List()}

      members.flatMap{ member:Symbol =>
	if(member.name.toString == "apply"){
	  Some(SymbolInfoLight(sym, member.tpe))
	}
	else{None}
      }
    }

    def apply(sym:Symbol):SymbolInfoLight = SymbolInfoLight(sym, sym.tpe)

    def apply(sym:Symbol, tpe:Type):SymbolInfoLight = {
      new SymbolInfoLight(
	sym.name.toString,
	tpe.underlying.toString,
	cacheType(tpe.underlying),
	Helpers.isArrowType(tpe.underlying)
      )
    }

    def nullInfo() = {
      new SymbolInfoLight("NA", "NA", -1, false)
    }
  }



  object ArrowTypeInfo{

    def apply(tpe:Type):ArrowTypeInfo = {
      tpe match{
	case tpe:MethodType => apply(tpe, tpe.paramTypes, tpe.params, tpe.resultType)
	case tpe:PolyType => apply(tpe, tpe.paramTypes, tpe.params, tpe.resultType)
	case _ => nullInfo
      }
    }

    def apply(tpe:Type, paramTypes:Iterable[Type], paramNames:Iterable[Symbol], resultType:Type):ArrowTypeInfo = {
      new ArrowTypeInfo(
	tpe.toString, 
	cacheType(tpe), 
	TypeInfo(tpe.resultType), 
	tpe.paramTypes.map(t => TypeInfo(t)))
    }

    def nullInfo() = {
      new ArrowTypeInfo("NA", -1, TypeInfo.nullInfo, List())
    }
  }



  object TypeInspectInfo{
    def nullInfo() = {
      new TypeInspectInfo(TypeInfo.nullInfo(), List())
    }
  }


}
