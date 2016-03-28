/**
 * Copyright (C) 2015 Kaj Magnus Lindberg
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
import com.debiki.core.Prelude._
import com.google.common.net.InetAddresses
import java.{sql => js, util => ju}
import java.net.InetAddress
import scala.collection.{mutable, immutable}
import scala.collection.mutable.ArrayBuffer
import Rdb._
import RdbUtil._


/** Manages blocks (i.e. bans) of ip addresses, browser id cookies etc.
  */
trait BlocksSiteDaoMixin extends SiteTransaction {
  self: RdbSiteDao =>


  override def insertBlock(block: Block) {
    val statement = s"""
      insert into blocks3(
        site_id,
        block_type,
        blocked_at,
        blocked_till,
        blocked_by_id,
        ip,
        browser_id_cookie)
      values (
        ?, ?,
        ? at time zone 'UTC',
        ? at time zone 'UTC',
        ?, ?::inet, ?)
      """
    val values = List[AnyRef](
      siteId,
      NullVarchar,
      block.blockedAt.asTimestamp,
      block.blockedTill.orNullTimestamp,
      block.blockedById.asAnyRef,
      block.ip.map(_.getHostAddress).orNullVarchar,
      block.browserIdCookie.orNullVarchar)
    runUpdateSingleRow(statement, values)
  }


  def unblockIp(ip: InetAddress) {
    deleteBlock("ip = ?::inet", ip.getHostAddress)
  }


  def unblockBrowser(browserIdCookie: String) {
    deleteBlock("browser_id_cookie = ?", browserIdCookie)
  }


  private def deleteBlock(whereTest: String, value: String) {
    val statement = s"""
     delete from blocks3
     where site_id = ? and $whereTest
     """
    runUpdateSingleRow(statement, List(siteId, value))
  }


  override def loadBlocks(ip: String, browserIdCookie: String): immutable.Seq[Block] = {
    val query = """
      select
        blocked_at,
        blocked_till,
        blocked_by_id,
        ip,
        browser_id_cookie
      from blocks3
      where
        site_id = ? and (ip = ?::inet or browser_id_cookie = ?)
      """
    var result = ArrayBuffer[Block]()
    runQuery(query, List(siteId, ip, browserIdCookie), rs => {
      while (rs.next()) {
        result += getBlock(rs)
      }
    })
    result.toVector
  }


  private def getBlock(rs: js.ResultSet) =
    Block(
      ip = Option(rs.getString("ip")).map(InetAddresses.forString),
      browserIdCookie = Option(rs.getString("browser_id_cookie")),
      blockedById = rs.getInt("blocked_by_id"),
      blockedAt = getDate(rs, "blocked_at"),
      blockedTill = getOptionalDate(rs, "blocked_till"))

}
