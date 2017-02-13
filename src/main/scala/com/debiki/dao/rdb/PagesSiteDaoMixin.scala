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

import collection.immutable
import collection.mutable.ArrayBuffer
import com.debiki.core._
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import scala.collection.mutable
import Rdb._
import RdbUtil._
import PostsSiteDaoMixin._


/** Loads and saves pages and cached page content html.
  */
trait PagesSiteDaoMixin extends SiteTransaction {
  self: RdbSiteTransaction =>


  def markPagesWithUserAvatarAsStale(userId: UserId) {
    // Currently a user's avatar is shown in posts written by him/her.
    val statement = s"""
      update pages3
        set version = version + 1, updated_at = now_utc()
        where site_id = ?
          and page_id in (
            select distinct page_id from posts3
            where site_id = ? and created_by_id = ?)
      """
    runUpdate(statement, List(siteId, siteId, userId.asAnyRef))
  }


  def markSectionPageContentHtmlAsStale(categoryId: CategoryId) {
    val statement = s"""
      update page_html3 h
        set page_version = -1, updated_at = now_utc()
        where site_id = ?
          and page_id = (
            select page_id from categories3
            where site_id = ? and id = ?)
      """
    runUpdateSingleRow(statement, List(siteId, siteId, categoryId.asAnyRef))
  }


  override def loadCachedPageContentHtml(pageId: PageId): Option[(String, CachedPageVersion)] = {
    val query = s"""
      select site_version, page_version, app_version, data_hash, html
      from page_html3
      where site_id = ? and page_id = ?
      """
    runQuery(query, List(siteId, pageId.asAnyRef), rs => {
      if (!rs.next())
        return None

      val html = rs.getString("html")
      val version = getCachedPageVersion(rs)
      dieIf(rs.next(), "DwE5KGF2")

      Some(html, version)
    })
  }


  override def saveCachedPageContentHtmlPerhapsBreakTransaction(
        pageId: PageId, version: CachedPageVersion, html: String): Boolean = {
    // 1) BUG Race condition: If the page was just deleted, something else might just
    // have removed the cached version. And here we might reinsert an old version again.
    // Rather harmless though — other parts of the system will ignore the page if it
    // doesn't exist. But this might waste a tiny bit disk space.
    // 2) Do an upsert. In 9.4 there's built in support for this, but for now:
    val updateStatement = s"""
      update page_html3
        set site_version = ?,
            page_version = ?,
            app_version = ?,
            data_hash = ?,
            updated_at = now_utc(),
            html = ?
        where site_id = ?
          and page_id = ?
          -- Don't overwrite a newer version with an older version. But do overwrite
          -- if the version is the same, because we might be rerendering because
          -- the app version (i.e. the html generation code) has been changed.
          and site_version <= ?
          and page_version <= ?
      """
    val rowFound = runUpdateSingleRow(updateStatement, List(
      version.siteVersion.asAnyRef, version.pageVersion.asAnyRef,
      version.appVersion, version.dataHash, html, siteId, pageId,
      version.siteVersion.asAnyRef, version.pageVersion.asAnyRef))

    if (!rowFound) {
      val insertStatement = s"""
        insert into page_html3 (
          site_id, page_id,
          site_version, page_version, app_version,
          data_hash, updated_at, html)
        values (?, ?, ?, ?, ?, ?, now_utc(), ?)
        """
      try {
        runUpdateSingleRow(insertStatement, List(
          siteId, pageId,
          version.siteVersion.asAnyRef, version.pageVersion.asAnyRef, version.appVersion,
          version.dataHash, html))
      }
      catch {
        case exception: js.SQLException if isUniqueConstrViolation(exception) =>
          // Ok, something else generated and cached the page at the same time.
          // However now the transaction is broken.
          return false
      }
    }

    true
  }

}

