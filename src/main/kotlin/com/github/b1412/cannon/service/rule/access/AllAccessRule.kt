package com.github.b1412.cannon.service.rule.access

import com.github.b1412.cannon.entity.Permission
import org.springframework.stereotype.Component

@Component
class AllAccessRule : AccessRule {
    override val ruleName: String
        get() = "all"

    override fun exec(permission: Permission): Map<String, String> {
        return mapOf()
    }
}