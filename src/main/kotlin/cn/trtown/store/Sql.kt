package cn.trtown.store

import java.sql.ResultSet
import java.util.UUID

internal fun ResultSet.uuid(col: String): UUID = UUID.fromString(getString(col))

internal fun ResultSet.uuidOrNull(col: String): UUID? = getString(col)?.let { UUID.fromString(it) }

internal fun ResultSet.intOrNull(col: String): Int? = getInt(col).takeUnless { wasNull() }

internal fun ResultSet.bool(col: String): Boolean = getInt(col) != 0

internal fun Boolean.asInt(): Int = if (this) 1 else 0
