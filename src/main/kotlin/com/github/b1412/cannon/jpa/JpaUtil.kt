package com.github.b1412.cannon.jpa

import arrow.core.Option
import arrow.core.getOrElse
import arrow.core.toOption
import mu.KotlinLogging
import org.hibernate.graph.EntityGraphs
import org.hibernate.graph.GraphParser
import javax.persistence.EntityGraph
import javax.persistence.EntityManager
import javax.persistence.criteria.*
import javax.persistence.metamodel.Attribute
import javax.persistence.metamodel.EntityType
import javax.persistence.metamodel.PluralAttribute
import javax.persistence.metamodel.SingularAttribute

private val logger = KotlinLogging.logger {}

interface UrlMapper {
    fun paramsToFilter(params: Map<String, String>): Map<String, String>
    fun extract(item: Map.Entry<String, String>, params: Map<String, String>): Pair<String, String>
}

class V1UrlMapper : UrlMapper {
    override fun paramsToFilter(params: Map<String, String>): Map<String, String> {
        return params.filter {
            it.key.contains("f_") && !it.key.endsWith("_op")
        }
    }

    override fun extract(item: Map.Entry<String, String>, params: Map<String, String>): Pair<String, String> {
        val operator = params[item.key + "_op"].toOption().getOrElse { "=" }
        val field = item.key.replace("f_", "")
        return Pair(field, operator)
    }
}


class V2UrlMapper : UrlMapper {
    override fun paramsToFilter(params: Map<String, String>): Map<String, String> {
        return params.filter {
            it.key.contains("_")
        }
    }

    override fun extract(item: Map.Entry<String, String>, params: Map<String, String>): Pair<String, String> {
        val list = item.key.split("_")
        return Pair(list[0], list[1])
    }
}

object JpaUtil {
    fun <T> createPredicate(filter: Map<String, String>, root: Root<T>, cb: CriteriaBuilder, urlMapper: UrlMapper = V1UrlMapper()): Option<Predicate> {
        val filterFields = urlMapper.paramsToFilter(filter)
        if (filterFields.isEmpty()) {
            return Option.empty()
        }
        var entityType = root.model
        val predicates = filterFields.map {
            val value = it.value
            val (field, operator) = urlMapper.extract(it, filter)
            val searchPath: Path<Any>
            val fields = field.split(".")
            var javaType: Class<*>? = null
            val convertedValues: List<Any>
            if (fields.size > 1) {
                var join: Join<Any, Any> = root.join(fields[0])
                for (i in 1 until fields.size - 1) {
                    join = join.join(fields[i])
                }
                searchPath = join.get(fields[fields.size - 1])
                fields.windowed(2, 1).forEach { (e, f) ->
                    entityType = getReferenceEntityType(entityType, e)
                    javaType = getJavaType(entityType, f)
                }
            } else {
                javaType = getJavaType(entityType, field)
                searchPath = root.get(field)
            }
            logger.debug { "javaType $javaType" }
            convertedValues = when {
                javaType!!.isAssignableFrom(java.lang.Long::class.java) -> value.split(",").map { v -> v.toLong() }
                javaType!!.isAssignableFrom(java.lang.Boolean::class.java) -> value.split(",").map { v -> v.toBoolean() }
                else -> value.split(",")
            }
            when (operator) {
                "like" -> {
                    cb.like(searchPath as Path<String>, "%$value%")
                }
                "in" -> {
                    searchPath.`in`(*convertedValues.toTypedArray())
                }
                "between" -> {
                    if (javaType!!.isAssignableFrom(java.lang.Long::class.java)) {
                        val (lowerbond, upperbond) = convertedValues.map { v -> v as Long }
                        cb.between(searchPath as Path<Long>, lowerbond, upperbond)
                    } else {
                        throw  IllegalArgumentException()
                    }
                }
                else -> {
                    cb.equal(searchPath, convertedValues.first())
                }
            }
        }.reduce { l, r ->
            cb.and(l, r)
        }
        return predicates.toOption()
    }

    fun <T> createEntityGraphFromURL(entityManager: EntityManager, domainClass: Class<T>, filter: Map<String, String>): EntityGraph<T> {
        val embedded = filter["embedded"]
                .toOption()
                .filter { it.isNotBlank() }
                .map { it.split(",") }
                .getOrElse {
                    listOf()
                }
        logger.debug { "embedded $embedded" }
        val graphs = embedded.map {
            it.split(".").reversed().reduce { l, r ->
                val s = "$r($l)"
                s
            }
        }.map {
            GraphParser.parse(domainClass, it, entityManager)
        }
        return EntityGraphs.merge(entityManager, domainClass, *graphs.toTypedArray())
    }

    private fun getJavaType(entityType: EntityType<*>, field: String): Class<*> {
        val argumentEntityAttribute = entityType.getAttribute(field)
        return if (argumentEntityAttribute is PluralAttribute<*, *, *>) argumentEntityAttribute.elementType.javaType else argumentEntityAttribute.javaType
    }

    private fun <T> getReferenceEntityType(entityType: EntityType<T>, field: String): EntityType<T> {
        val attribute = entityType.getAttribute(field)
        return when {
            attribute.persistentAttributeType == Attribute.PersistentAttributeType.ONE_TO_MANY || attribute.persistentAttributeType == Attribute.PersistentAttributeType.MANY_TO_MANY -> {
                val foreignType = (attribute as PluralAttribute<*, *, *>).elementType as EntityType<T>
                foreignType
            }
            attribute.persistentAttributeType == Attribute.PersistentAttributeType.MANY_TO_ONE || attribute.persistentAttributeType == Attribute.PersistentAttributeType.ONE_TO_ONE -> {
                val foreignType = (attribute as SingularAttribute<*, *>).type as EntityType<T>
                foreignType
            }
            else -> throw  IllegalArgumentException()
        }
    }
}
