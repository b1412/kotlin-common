package com.github.b1412.cannon.dao.base

import com.github.b1412.cannon.jpa.UrlMapper
import com.github.b1412.cannon.jpa.V1UrlMapper
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.repository.NoRepositoryBean
import java.io.Serializable

@NoRepositoryBean
interface BaseDao<T, ID : Serializable> : JpaRepository<T, ID>, JpaSpecificationExecutor<T> {
    fun searchByFilter(filter: Map<String, String>, pageable: Pageable, urlMapper: UrlMapper = V1UrlMapper()): Page<T>
}

