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


/** Saves and deletes notifications.
  */
trait NotificationsSiteDaoMixin extends SiteDbDao {
  self: RdbSiteDao =>


  def saveDeleteNotifications(notifications: Notifications) {
    db.transaction { implicit connection =>
      notifications.toCreate foreach { createNotf(_)(connection) }
      notifications.toDelete foreach { deleteNotf(_)(connection) }
    }
  }


  private def createNotf(notf: Notification)(implicit connection: js.Connection) {
    val sql = """
      insert into DW1_NOTIFICATIONS(
        SITE_ID, CREATED_AT, NOTF_TYPE,
        PAGE_ID, POST_ID, ACTION_ID,
        BY_USER_ID, TO_USER_ID)
      values (?, ?, ?, ?, ?, ?, ?, ?)
      """

    val values = mutable.ArrayBuffer[AnyRef](
      siteId,
      d2ts(notf.createdAt),
      notificationTypeToFlag(notf))

    notf match {
      case postNotf: Notification.NewPost =>
        values += postNotf.pageId
        values += postNotf.postId.asAnyRef
        values += NullInt
        values += postNotf.byUserId
        values += postNotf.toUserId
    }

    db.update(sql, values.toList)
  }


  private def deleteNotf(notfToDelete: NotificationToDelete)(implicit connection: js.Connection) {
    val (sql, values: List[AnyRef]) = notfToDelete match {
      case mentionToDelete: NotificationToDelete.MentionToDelete =>
        val sql = """
          delete from DW1_NOTIFICATIONS
          where SITE_ID = ?
            and NOTF_TYPE = 'M'
            and PAGE_ID = ?
            and POST_ID = ?
            and TO_USER_ID = ?"""
        val values = List(siteId, mentionToDelete.pageId, mentionToDelete.postId.asAnyRef,
          mentionToDelete.toUserId)
        (sql, values)
      case postToDelete: NotificationToDelete.NewPostToDelete =>
        val sql = """
          delete from DW1_NOTIFICATIONS
          where SITE_ID = ?
            and NOTF_TYPE in ('M', 'R', 'N') -- for now
            and PAGE_ID = ?
            and POST_ID = ?"""
        val values = List(siteId, postToDelete.pageId, postToDelete.postId.asAnyRef)
        (sql, values)
    }

    db.update(sql, values)
  }


  def loadNotificationsForRole(roleId: RoleId): Seq[Notification] = {
    val numToLoad = 50 // for now
    val notfsToMail = systemDaoSpi.loadNotfsImpl(   // COULD specify consumers
        numToLoad, Some(siteId), userIdOpt = Some(roleId))
    // All loaded notifications are to `roleId` only.
    notfsToMail(siteId)
  }


  def updateNotificationSkipEmail(notifications: Seq[Notification]) {
    db.transaction { implicit connection =>
      updateNotificationConnectToEmail(notifications, email = None)
    }
  }


  def updateNotificationConnectToEmail(notifications: Seq[Notification], email: Option[Email])
        (implicit connection: js.Connection) {
    notifications foreach {
      connectNotificationToEmail(_, email)(connection)
    }
  }


  def connectNotificationToEmail(notification: Notification, email: Option[Email])
        (connection: js.Connection) {

    val baseSql =
      "update DW1_NOTIFICATIONS set EMAIL_ID = ?, EMAIL_CREATED_AT = ? where "

    val tyype = notificationTypeToFlag(notification)

    val (whereClause, values): (String, List[AnyRef]) = notification match {
      case newPost: Notification.NewPost =>
        ("SITE_ID = ? and NOTF_TYPE = ? and PAGE_ID = ? and POST_ID = ? and TO_USER_ID = ?",
          List(siteId, tyype, newPost.pageId, newPost.postId.asAnyRef, newPost.toUserId))
    }

    db.update(
      baseSql + whereClause,
      email.map(_.id).orNullVarchar :: d2ts(new ju.Date) :: values)(connection)
  }


  def notificationTypeToFlag(notification: Notification): AnyRef = notification match {
    case newPostNotf: Notification.NewPost =>
      (newPostNotf.notfType match {
        case Notification.NewPostNotfType.DirectReply => "R"
        case Notification.NewPostNotfType.Mention => "M"
        case Notification.NewPostNotfType.NewPost => "N"
      }).asAnyRef
  }
}