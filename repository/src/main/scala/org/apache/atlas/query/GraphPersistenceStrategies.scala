/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.atlas.query

import java.util
import java.util.Date

import scala.collection.JavaConversions.seqAsJavaList

import org.apache.atlas.query.Expressions.{ComparisonExpression, ExpressionException}
import org.apache.atlas.query.TypeUtils.FieldInfo
import org.apache.atlas.repository.graph.{GraphHelper, GraphBackedMetadataRepository}
import org.apache.atlas.repository.RepositoryException
import org.apache.atlas.repository.graphdb._
import org.apache.atlas.typesystem.persistence.Id
import org.apache.atlas.typesystem.types.DataTypes._
import org.apache.atlas.typesystem.persistence.Id
import org.apache.atlas.typesystem.types._
import org.apache.atlas.typesystem.{ITypedInstance, ITypedReferenceableInstance}


import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
 * Represents the Bridge between the QueryProcessor and the Graph Persistence scheme used.
 * Some of the behaviors captured are:
 * - how is type and id information stored in the Vertex that represents an [[ITypedReferenceableInstance]]
 * - how are edges representing trait and attribute relationships labeled.
 * - how are attribute names mapped to Property Keys in Vertices.
 *
 * This is a work in progress.
 * 
 */
trait GraphPersistenceStrategies {

    @throws(classOf[RepositoryException])
    def getGraph() : AtlasGraph[_,_]
    
    def getSupportedGremlinVersion() : GremlinVersion = getGraph().getSupportedGremlinVersion;
    def generatePersisentToLogicalConversionExpression(expr: String, t: IDataType[_]) : String = getGraph().generatePersisentToLogicalConversionExpression(expr, t);
    def isPropertyValueConversionNeeded(attrType: IDataType[_]) : Boolean = getGraph().isPropertyValueConversionNeeded(attrType);
    
    def initialQueryCondition = if (getGraph().requiresInitialIndexedPredicate()) { s""".${getGraph().getInitialIndexedPredicate}""" } else {""};
   
    /**
     * Name of attribute used to store typeName in vertex
     */
    def typeAttributeName: String

    /**
     * Name of attribute used to store super type names in vertex.
     */
    def superTypeAttributeName: String

    /**
     * Name of attribute used to store guid in vertex
     */
    def idAttributeName : String

    /**
      * Name of attribute used to store state in vertex
      */
    def stateAttributeName : String

    /**
     * Given a dataType and a reference attribute, how is edge labeled
     */
    def edgeLabel(iDataType: IDataType[_], aInfo: AttributeInfo): String

    def traitLabel(cls: IDataType[_], traitName: String): String

    def instanceToTraitEdgeDirection : String = "out"
    def traitToInstanceEdgeDirection = instanceToTraitEdgeDirection match {
      case "out" => "in"
      case "in" => "out"
      case x => x
    }

    /**
     * The propertyKey used to store the attribute in a Graph Vertex.
     * @param dataType
     * @param aInfo
     * @return
     */
    def fieldNameInVertex(dataType: IDataType[_], aInfo: AttributeInfo): String

    /**
     * from a vertex for an [[ITypedReferenceableInstance]] get the traits that it has.
     * @param v
     * @return
     */
    def traitNames(v: AtlasVertex[_,_]): java.util.List[String]

    def edgeLabel(fInfo: FieldInfo): String = fInfo match {
        case FieldInfo(dataType, aInfo, null, null) => edgeLabel(dataType, aInfo)
        case FieldInfo(dataType, aInfo, reverseDataType, null) => edgeLabel(reverseDataType, aInfo)
        case FieldInfo(dataType, null, null, traitName) => traitLabel(dataType, traitName)
    }

    def fieldPrefixInSelect(): String = {

        if(getSupportedGremlinVersion() == GremlinVersion.THREE) {
            //this logic is needed to remove extra results from
            //what is emitted by repeat loops.  Technically
            //for queries that don't have a loop in them we could just use "it"
            //the reason for this is that in repeat loops with an alias,
            //although the alias gets set to the right value, for some
            //reason the select actually includes all vertices that were traversed
            //through in the loop.  In these cases, we only want the last vertex
            //traversed in the loop to be selected.  The logic here handles that
            //case by converting the result to a list and just selecting the
            //last item from it.
            "((it as Vertex[]) as List<Vertex>).last()"
        }
        else {
            "it"
        }

    }   

    /**
     * extract the Id from a Vertex.
     * @param dataTypeNm the dataType of the instance that the given vertex represents
     * @param v
     * @return
     */
    def getIdFromVertex(dataTypeNm: String, v: AtlasVertex[_,_]): Id

    def constructInstance[U](dataType: IDataType[U], v: java.lang.Object): U

    def gremlinCompOp(op: ComparisonExpression) = {
         if( getSupportedGremlinVersion() == GremlinVersion.TWO) {
             gremlin2CompOp(op);
         }
         else {
            gremlin3CompOp(op);
         }
     }
    
     def gremlinPrimitiveOp(op: ComparisonExpression) = op.symbol match {
        case "=" => "=="
        case "!=" => "!="
        case ">" => ">"
        case ">=" => ">="
        case "<" => "<"
        case "<=" => "<="
        case _ => throw new ExpressionException(op, "Comparison operator not supported in Gremlin")
     }

    private def gremlin2CompOp(op: ComparisonExpression) = op.symbol match {
        case "=" => "T.eq"
        case "!=" => "T.neq"
        case ">" => "T.gt"
        case ">=" => "T.gte"
        case "<" => "T.lt"
        case "<=" => "T.lte"
        case _ => throw new ExpressionException(op, "Comparison operator not supported in Gremlin")
    }

    private def gremlin3CompOp(op: ComparisonExpression) = op.symbol match {
        case "=" => "eq"
        case "!=" => "neq"
        case ">" => "gt"
        case ">=" => "gte"
        case "<" => "lt"
        case "<=" => "lte"
        case _ => throw new ExpressionException(op, "Comparison operator not supported in Gremlin")
    }

    def loopObjectExpression(dataType: IDataType[_]) = {
      _typeTestExpression(dataType.getName, "it.object")
    }

    def addGraphVertexPrefix(preStatements : Traversable[String]) = !collectTypeInstancesIntoVar

    /**
     * Controls behavior of how instances of a Type are discovered.
     * - query is generated in a way that indexes are exercised using a local set variable across multiple lookups
     * - query is generated using an 'or' expression.
     *
     * '''This is a very bad idea: controlling query execution behavior via query generation.''' But our current
     * knowledge of seems to indicate we have no choice. See
     * [[https://groups.google.com/forum/#!topic/gremlin-users/n1oV86yr4yU discussion in Gremlin group]].
     * Also this seems a fragile solution, dependend on the memory requirements of the Set variable.
     * For now enabling via the '''collectTypeInstancesIntoVar''' behavior setting. Reverting back would require
     * setting this to false.
     *
     * Long term have to get to the bottom of Gremlin:
     * - there doesn't seem to be way to see the physical query plan. Maybe we should directly interface with Titan.
     * - At least from querying perspective a columnar db maybe a better route. Daniel Abadi did some good work
     *   on showing how to use a columnar store as a Graph Db.
     *
     *
     * @return
     */
    def collectTypeInstancesIntoVar = true

    def typeTestExpression(typeName : String, intSeq : IntSequence) : Seq[String] = {
        if (collectTypeInstancesIntoVar)
            typeTestExpressionMultiStep(typeName, intSeq)
        else
            typeTestExpressionUsingFilter(typeName)
    }

    private def typeTestExpressionUsingFilter(typeName : String) : Seq[String] = {
      Seq(s"""filter${_typeTestExpression(typeName, "it")}""")
    }

    /**
     * type test expression that ends up in the emit clause in
     * loop/repeat steps and a few other places
     */
    private def _typeTestExpression(typeName: String, itRef: String): String = {

        if( getSupportedGremlinVersion() == GremlinVersion.TWO) {
             s"""{(${itRef}.'${typeAttributeName}' == '${typeName}') |
       | (${itRef}.'${superTypeAttributeName}' ?
       | ${itRef}.'${superTypeAttributeName}'.contains('${typeName}') : false)}""".
          stripMargin.replace(System.getProperty("line.separator"), "")
        }
        else {
            //gremlin 3
            s"""has('${typeAttributeName}',eq('${typeName}')).or().has('${superTypeAttributeName}',eq('${typeName}'))"""
        }
  }

    private def propertyValueSet(vertexRef : String, attrName: String) : String = {
        s"""org.apache.tinkerpop.gremlin.util.iterator.IteratorUtils.set(${vertexRef}.values('${attrName})"""
    }

    private def typeTestExpressionMultiStep(typeName : String, intSeq : IntSequence) : Seq[String] = {

        val varName = s"_var_${intSeq.next}"
        Seq(
            newSetVar(varName),
            fillVarWithTypeInstances(typeName, varName),
            fillVarWithSubTypeInstances(typeName, varName),
            if(getSupportedGremlinVersion() == GremlinVersion.TWO) {
                s"$varName._()"
            }
            else {
                //this bit of groovy magic converts the set of vertices in varName into
                //a String containing the ids of all the vertices.  This becomes the argument
                //to g.V().  This is needed because Gremlin 3 does not support
                // _()
                //s"g.V(${varName}.collect{it.id()} as String[])"
                s"g.V(${varName} as Object[])${initialQueryCondition}"
            }
        )
    }

    private def newSetVar(varName : String) = s"def $varName = [] as Set"

    private def fillVarWithTypeInstances(typeName : String, fillVar : String) = {
        s"""g.V().has("${typeAttributeName}", "${typeName}").fill($fillVar)"""
    }

    private def fillVarWithSubTypeInstances(typeName : String, fillVar : String) = {
        s"""g.V().has("${superTypeAttributeName}", "${typeName}").fill($fillVar)"""
    }  
}

import scala.language.existentials;
import org.apache.atlas.repository.RepositoryException
import org.apache.atlas.repository.RepositoryException
import org.apache.atlas.repository.RepositoryException
import org.apache.atlas.repository.RepositoryException

case class GraphPersistenceStrategy1(g: AtlasGraph[_,_]) extends GraphPersistenceStrategies {

    val typeAttributeName = "typeName"
    val superTypeAttributeName = "superTypeNames"
    val idAttributeName = "guid"
    val stateAttributeName = "state"

    override def getGraph() : AtlasGraph[_,_] =  {
        return g;
    }   
    
    def edgeLabel(dataType: IDataType[_], aInfo: AttributeInfo) = s"__${dataType.getName}.${aInfo.name}"

    def edgeLabel(propertyName: String) = s"__${propertyName}"

    def traitLabel(cls: IDataType[_], traitName: String) = s"${cls.getName}.$traitName"

    def fieldNameInVertex(dataType: IDataType[_], aInfo: AttributeInfo) = GraphHelper.getQualifiedFieldName(dataType, aInfo.name)

    def getIdFromVertex(dataTypeNm: String, v: AtlasVertex[_,_]): Id =
        new Id(v.getId.toString, 0, dataTypeNm)

    def traitNames(v: AtlasVertex[_,_]): java.util.List[String] = {
        val s = v.getProperty("traitNames", classOf[String])
        if (s != null) {
            Seq[String](s.split(","): _*)
        } else {
            Seq()
        }
    }

    def constructInstance[U](dataType: IDataType[U], v: AnyRef): U = {
        dataType.getTypeCategory match {
            case DataTypes.TypeCategory.PRIMITIVE => dataType.convert(v, Multiplicity.OPTIONAL)
            case DataTypes.TypeCategory.ARRAY =>
                dataType.convert(v, Multiplicity.OPTIONAL)
            case DataTypes.TypeCategory.STRUCT
              if dataType.getName == TypeSystem.getInstance().getIdType.getName => {
              val sType = dataType.asInstanceOf[StructType]
              val sInstance = sType.createInstance()
              val tV = v.asInstanceOf[AtlasVertex[_,_]]
              sInstance.set(TypeSystem.getInstance().getIdType.typeNameAttrName,
                tV.getProperty(typeAttributeName, classOf[java.lang.String]))
              sInstance.set(TypeSystem.getInstance().getIdType.idAttrName,
                tV.getProperty(idAttributeName, classOf[java.lang.String]))
              dataType.convert(sInstance, Multiplicity.OPTIONAL)
            }
            case DataTypes.TypeCategory.STRUCT => {
                val sType = dataType.asInstanceOf[StructType]
                val sInstance = sType.createInstance()
                loadStructInstance(sType, sInstance, v.asInstanceOf[AtlasVertex[_,_]])
                dataType.convert(sInstance, Multiplicity.OPTIONAL)
            }
            case DataTypes.TypeCategory.TRAIT => {
                val tType = dataType.asInstanceOf[TraitType]
                val tInstance = tType.createInstance()
                /*
                 * this is not right, we should load the Instance associated with this trait.
                 * for now just loading the trait struct.
                 */
                loadStructInstance(tType, tInstance, v.asInstanceOf[AtlasVertex[_,_]])
                dataType.convert(tInstance, Multiplicity.OPTIONAL)
            }
            case DataTypes.TypeCategory.CLASS => {
                val cType = dataType.asInstanceOf[ClassType]
                val cInstance = constructClassInstance(dataType.asInstanceOf[ClassType], v.asInstanceOf[AtlasVertex[_,_]])
                dataType.convert(cInstance, Multiplicity.OPTIONAL)
            }
            case DataTypes.TypeCategory.ENUM => dataType.convert(v, Multiplicity.OPTIONAL)
            case x => throw new UnsupportedOperationException(s"load for ${dataType} not supported")
        }
    }

    def loadStructInstance(dataType: IConstructableType[_, _ <: ITypedInstance],
                           typInstance: ITypedInstance, v: AtlasVertex[_,_]): Unit = {
        import scala.collection.JavaConversions._
        dataType.fieldMapping().fields.foreach { t =>
            val fName = t._1
            val aInfo = t._2
            loadAttribute(dataType, aInfo, typInstance, v)
        }
    }

    def constructClassInstance(dataType: ClassType, v: AtlasVertex[_,_]): ITypedReferenceableInstance = {
        val id = getIdFromVertex(dataType.name, v)
        val tNms = traitNames(v)
        val cInstance = dataType.createInstance(id, tNms: _*)
        // load traits
        tNms.foreach { tNm =>
            val tLabel = traitLabel(dataType, tNm)
            val edges = v.getEdges(AtlasEdgeDirection.OUT, tLabel)
            val tVertex = edges.iterator().next().getInVertex().asInstanceOf[AtlasVertex[_,_]]
            val tType = TypeSystem.getInstance().getDataType[TraitType](classOf[TraitType], tNm)
            val tInstance = cInstance.getTrait(tNm).asInstanceOf[ITypedInstance]
            loadStructInstance(tType, tInstance, tVertex)
        }
        loadStructInstance(dataType, cInstance, v)
        cInstance
    }

    def loadAttribute(dataType: IDataType[_], aInfo: AttributeInfo, i: ITypedInstance, v: AtlasVertex[_,_]): Unit = {
        aInfo.dataType.getTypeCategory match {
            case DataTypes.TypeCategory.PRIMITIVE => loadPrimitiveAttribute(dataType, aInfo, i, v)
            case DataTypes.TypeCategory.ENUM => loadEnumAttribute(dataType, aInfo, i, v)
            case DataTypes.TypeCategory.ARRAY =>
                loadArrayAttribute(dataType, aInfo, i, v)
            case DataTypes.TypeCategory.MAP =>
                throw new UnsupportedOperationException(s"load for ${aInfo.dataType()} not supported")
            case DataTypes.TypeCategory.STRUCT => loadStructAttribute(dataType, aInfo, i, v)
            case DataTypes.TypeCategory.TRAIT =>
                throw new UnsupportedOperationException(s"load for ${aInfo.dataType()} not supported")
            case DataTypes.TypeCategory.CLASS => loadStructAttribute(dataType, aInfo, i, v)
        }
    }

    private def loadEnumAttribute(dataType: IDataType[_], aInfo: AttributeInfo, i: ITypedInstance, v: AtlasVertex[_,_])
    : Unit = {
        val fName = fieldNameInVertex(dataType, aInfo)
        i.setInt(aInfo.name, v.getProperty(fName, classOf[java.lang.Integer]))
    }

    private def loadPrimitiveAttribute(dataType: IDataType[_], aInfo: AttributeInfo,
                                       i: ITypedInstance, v: AtlasVertex[_,_]): Unit = {
        val fName = fieldNameInVertex(dataType, aInfo)
        aInfo.dataType() match {
            case x: BooleanType => i.setBoolean(aInfo.name, v.getProperty(fName, classOf[java.lang.Boolean]))
            case x: ByteType => i.setByte(aInfo.name, v.getProperty(fName, classOf[java.lang.Byte]))
            case x: ShortType => i.setShort(aInfo.name, v.getProperty(fName, classOf[java.lang.Short]))
            case x: IntType => i.setInt(aInfo.name, v.getProperty(fName, classOf[java.lang.Integer]))
            case x: LongType => i.setLong(aInfo.name, v.getProperty(fName, classOf[java.lang.Long]))
            case x: FloatType => i.setFloat(aInfo.name, v.getProperty(fName, classOf[java.lang.Float]))
            case x: DoubleType => i.setDouble(aInfo.name, v.getProperty(fName, classOf[java.lang.Double]))
            case x: StringType => i.setString(aInfo.name, v.getProperty(fName, classOf[java.lang.String]))
            case x: DateType => {
                                  val dateVal = v.getProperty(fName, classOf[java.lang.Long])
                                  i.setDate(aInfo.name, new Date(dateVal))
                                }
            case _ => throw new UnsupportedOperationException(s"load for ${aInfo.dataType()} not supported")
        }
    }


    private def loadArrayAttribute[T](dataType: IDataType[_], aInfo: AttributeInfo,
                                    i: ITypedInstance, v: AtlasVertex[_,_]): Unit = {
        import scala.collection.JavaConversions._
        val list: java.util.List[_] = v.getListProperty(aInfo.name)
        val arrayType: DataTypes.ArrayType = aInfo.dataType.asInstanceOf[ArrayType]

        var values = new util.ArrayList[Any]
        list.foreach( listElement =>
            values += mapVertexToCollectionEntry(v, aInfo, arrayType.getElemType, i, listElement)
        )
        i.set(aInfo.name, values)
    }

    private def loadStructAttribute(dataType: IDataType[_], aInfo: AttributeInfo,
                                    i: ITypedInstance, v: AtlasVertex[_,_], edgeLbl: Option[String] = None): Unit = {
        val eLabel = edgeLbl match {
            case Some(x) => x
            case None => edgeLabel(FieldInfo(dataType, aInfo, null))
        }
        val edges = v.getEdges(AtlasEdgeDirection.OUT, eLabel)
        val sVertex = edges.iterator().next().getInVertex().asInstanceOf[AtlasVertex[_,_]]
        if (aInfo.dataType().getTypeCategory == DataTypes.TypeCategory.STRUCT) {
            val sType = aInfo.dataType().asInstanceOf[StructType]
            val sInstance = sType.createInstance()
            loadStructInstance(sType, sInstance, sVertex)
            i.set(aInfo.name, sInstance)
        } else {
            val cInstance = constructClassInstance(aInfo.dataType().asInstanceOf[ClassType], sVertex)
            i.set(aInfo.name, cInstance)
        }
    }



    private def mapVertexToCollectionEntry(instanceVertex: AtlasVertex[_,_], attributeInfo: AttributeInfo, elementType: IDataType[_], i: ITypedInstance,  value: Any): Any = {
        elementType.getTypeCategory match {
            case DataTypes.TypeCategory.PRIMITIVE => value
            case DataTypes.TypeCategory.ENUM => value
            case DataTypes.TypeCategory.STRUCT =>
                throw new UnsupportedOperationException(s"load for ${attributeInfo.dataType()} not supported")
            case DataTypes.TypeCategory.TRAIT =>
                throw new UnsupportedOperationException(s"load for ${attributeInfo.dataType()} not supported")
            case DataTypes.TypeCategory.CLASS => //loadStructAttribute(elementType, attributeInfo, i, v)
                throw new UnsupportedOperationException(s"load for ${attributeInfo.dataType()} not supported")
            case _ =>
                throw new UnsupportedOperationException(s"load for ${attributeInfo.dataType()} not supported")
        }
    }
}

