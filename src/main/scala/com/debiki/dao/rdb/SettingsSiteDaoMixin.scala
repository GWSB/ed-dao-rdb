/**
 * Copyright (C) 2014 Kaj Magnus Lindberg (born 1979)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.debiki.dao.rdb

import com.debiki.core._
import com.debiki.core.DbDao._
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import scala.collection.mutable
import Rdb._
import RdbUtil._


/** Creates, updates, deletes and loads settings for e.g. the whole webite, a section
  * of the site (e.g. a blog or a forum), single pages, and roles (users/groups).
  */
trait SettingsSiteDaoMixin extends SiteDbDao {
  self: RdbSiteDao =>


  def saveSetting(target: SettingsTarget, setting: SettingNameValue[_]) {
    db.transaction { connection =>
      val settingName = setting._1
      deleteSettingImpl(target, settingName)(connection)
      insertSettingImpl(target, setting)(connection)
    }
  }


  def loadSettings(targets: Seq[SettingsTarget]): Seq[RawSettings] = {
    db.withConnection { connection =>
      targets.map(loadSettings(_)(connection))
    }
  }


  /** Returns the number of settings deleted.
    */
  private def deleteSettingImpl(target: SettingsTarget, settingName: String)(
        implicit connection: js.Connection): Int = {
    val (sql, values) = target match {
      case SettingsTarget.WholeSite =>
        val sql = """
          delete from DW1_SETTINGS
          where TENANT_ID = ? and NAME = ? and TARGET = 'WholeSite'
          """
        (sql, List(siteId, settingName))
      case SettingsTarget.PageTree(rootPageId) =>
        val sql = """
          delete from DW1_SETTINGS
          where TENANT_ID = ? and NAME = ? and TARGET = 'PageTree' and PAGE_ID = ?
          """
        (sql, List(siteId, settingName, rootPageId))
      case SettingsTarget.SinglePage(pageId) =>
        val sql = """
          delete from DW1_SETTINGS
          where TENANT_ID = ? and NAME = ? and TARGET = 'SinglePage' and PAGE_ID = ?
          """
        (sql, List(siteId, settingName, pageId))
    }
    db.update(sql, values)
  }


  private def insertSettingImpl(target: SettingsTarget, setting: SettingNameValue[_])(
        implicit connection: js.Connection) {
    val settingName = setting._1
    val settingValue = setting._2

    val sql = """
      insert into DW1_SETTINGS(
        TENANT_ID, TARGET, PAGE_ID, NAME, TEXT_VALUE, LONG_VALUE, DOUBLE_VALUE)
      values (?, ?, ?, ?, ?, ?, ?)
      """

    val typeValue = target match {
      case SettingsTarget.WholeSite => "WholeSite"
      case _: SettingsTarget.PageTree => "PageTree"
      case _: SettingsTarget.SinglePage => "SinglePage"
    }

    val (textValue, longValue, doubleValue) = settingValue match {
      case x: String => (x, NullInt, NullDouble)
      case x: Int => (NullVarchar, x.asAnyRef, NullDouble)
      case x: Long => (NullVarchar, x.asAnyRef, NullDouble)
      case x: Float => (NullVarchar, NullInt, x.asAnyRef)
      case x: Double => (NullVarchar, NullInt, x.asAnyRef)
      case x: Boolean => assErr("DwE7GJ340", "Use 'T' and 'F' instead")
      case x => assErr("DwE77Xkf5", s"Unsupported value: `$x', type: ${classNameOf(x)}")
    }

    val pageIdOrNull = target match {
      case SettingsTarget.WholeSite => NullVarchar
      case SettingsTarget.PageTree(rootId) => rootId
      case SettingsTarget.SinglePage(id) => id
    }

    val values = List[AnyRef](
      siteId, typeValue, pageIdOrNull, settingName, textValue, longValue, doubleValue)

    db.update(sql, values)
  }


  private def loadSettings(target: SettingsTarget)(implicit connection: js.Connection): RawSettings = {

    val (sqlQuery, values) = target match {
      case SettingsTarget.WholeSite =>
        val sql = """
          select NAME, TEXT_VALUE, LONG_VALUE, DOUBLE_VALUE
          from DW1_SETTINGS
          where TENANT_ID = ? and TARGET = 'WholeSite'
          """
        (sql, List(siteId))
      case SettingsTarget.PageTree(rootPageId) =>
        val sql = """
          select NAME, TEXT_VALUE, LONG_VALUE, DOUBLE_VALUE
          from DW1_SETTINGS
          where TENANT_ID = ?
            and PAGE_ID = ?
            and TARGET = 'PageTree'
          """
        (sql, List(siteId, rootPageId))
      case SettingsTarget.SinglePage(pageId) =>
        val sql = """
          select NAME, TEXT_VALUE, LONG_VALUE, DOUBLE_VALUE
          from DW1_SETTINGS
          where TENANT_ID = ?
            and PAGE_ID = ?
            and TARGET = 'SinglePage'"""
        (sql, List(siteId, pageId))
    }

    val valuesBySettingName = mutable.HashMap[String, Any]()

    db.query(sqlQuery, values, rs => {
      while (rs.next()) {
        val name = rs.getString("NAME")
        val textValue = Option(rs.getString("TEXT_VALUE"))

        var longValue = Option(rs.getLong("LONG_VALUE"))
        if (rs.wasNull) longValue = None

        var doubleValue = Option(rs.getDouble("DOUBLE_VALUE"))
        if (rs.wasNull) doubleValue = None

        val value = textValue.orElse(longValue).orElse(doubleValue).getOrDie("DwE8fiG0")
        valuesBySettingName(name) = value
      }
    })

    RawSettings(target, valuesBySettingName.toMap)
  }

}
