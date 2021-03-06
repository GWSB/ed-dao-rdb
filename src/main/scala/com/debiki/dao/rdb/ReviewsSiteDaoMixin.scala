/**
 * Copyright (c) 2015 Kaj Magnus Lindberg
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
import com.debiki.core._
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import Rdb._
import RdbUtil.makeInListFor


/** Loads and saves ReviewTask:s.
  */
trait ReviewsSiteDaoMixin extends SiteTransaction {
  self: RdbSiteTransaction =>


  override def nextReviewTaskId(): ReviewTaskId = {
    val query = """
      select max(id) max_id from review_tasks3 where site_id = ?
      """
    runQueryFindExactlyOne(query, List(siteId.asAnyRef), rs => {
      val maxId = rs.getInt("max_id") // null becomes 0, fine
      maxId + 1
    })
  }


  override def upsertReviewTask(reviewTask: ReviewTask) {
    // Later, with Postgres 9.5, use its built-in upsert.
    val updateStatement = """
      update review_tasks3 set
        reasons = ?,
        created_by_id = ?,
        created_at = ?,
        created_at_rev_nr = ?,
        more_reasons_at = ?,
        completed_at = ?,
        completed_at_rev_nr = ?,
        completed_by_id = ?,
        invalidated_at = ?,
        resolution = ?,
        user_id = ?,
        page_id = ?,
        post_id = ?,
        post_nr = ?
      where site_id = ? and id = ?
      """
    val updateValues = List[AnyRef](
      ReviewReason.toLong(reviewTask.reasons).asAnyRef,
      reviewTask.createdById.asAnyRef,
      reviewTask.createdAt,
      reviewTask.createdAtRevNr.orNullInt,
      reviewTask.moreReasonsAt.orNullTimestamp,
      reviewTask.completedAt.orNullTimestamp,
      reviewTask.completedAtRevNr.orNullInt,
      reviewTask.completedById.orNullInt,
      reviewTask.invalidatedAt.orNullTimestamp,
      reviewTask.resolution.map(_.toInt).orNullInt,
      reviewTask.maybeBadUserId.asAnyRef,
      reviewTask.pageId.orNullVarchar,
      reviewTask.postId.orNullInt,
      reviewTask.postNr.orNullInt,
      siteId.asAnyRef,
      reviewTask.id.asAnyRef)

    val found = runUpdateSingleRow(updateStatement, updateValues)
    if (found)
      return

    val statement = """
      insert into review_tasks3(
        site_id,
        id,
        reasons,
        created_by_id,
        created_at,
        created_at_rev_nr,
        more_reasons_at,
        completed_at,
        completed_at_rev_nr,
        completed_by_id,
        invalidated_at,
        resolution,
        user_id,
        page_id,
        post_id,
        post_nr)
      values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """
    val values = List[AnyRef](
      siteId.asAnyRef,
      reviewTask.id.asAnyRef,
      ReviewReason.toLong(reviewTask.reasons).asAnyRef,
      reviewTask.createdById.asAnyRef,
      reviewTask.createdAt,
      reviewTask.createdAtRevNr.orNullInt,
      reviewTask.moreReasonsAt.orNullTimestamp,
      reviewTask.completedAt.orNullTimestamp,
      reviewTask.completedAtRevNr.orNullInt,
      reviewTask.completedById.orNullInt,
      reviewTask.invalidatedAt.orNullTimestamp,
      reviewTask.resolution.map(_.toInt).orNullInt,
      reviewTask.maybeBadUserId.asAnyRef,
      reviewTask.pageId.orNullVarchar,
      reviewTask.postId.orNullInt,
      reviewTask.postNr.orNullInt)
    runUpdateSingleRow(statement, values)
  }


  override def loadPendingPostReviewTask(postId: PostId): Option[ReviewTask] = {
    loadReviewTaskImpl(
      s"completed_at is null and invalidated_at is null and post_id = ?",
      Seq(postId.asAnyRef))
  }


  override def loadPendingPostReviewTask(postId: PostId, taskCreatedById: UserId)
        : Option[ReviewTask] = {
    loadReviewTaskImpl(
      s"completed_at is null and invalidated_at is null and created_by_id = ? and post_id = ?",
      Seq(taskCreatedById.asAnyRef, postId.asAnyRef))
  }


  override def loadReviewTask(id: ReviewTaskId): Option[ReviewTask] = {
    loadReviewTaskImpl("id = ?", List(id.asAnyRef))
  }


  private def loadReviewTaskImpl(whereClauses: String, values: Seq[AnyRef]): Option[ReviewTask] = {
    val query = i"""
      select * from review_tasks3 where site_id = ? and
      """ + whereClauses
    runQueryFindOneOrNone(query, (siteId.asAnyRef +: values).toList, rs => {
      readReviewTask(rs)
    })
  }


  override def loadReviewTasks(olderOrEqualTo: ju.Date, limit: Int): Seq[ReviewTask] = {
    // Sort by id, desc, if same timestamp, because higher id likely means more recent.
    val query = i"""
      select * from review_tasks3 where site_id = ? and created_at <= ?
      order by created_at desc, id desc limit ?
      """
    runQueryFindMany(query, List(siteId.asAnyRef, olderOrEqualTo, limit.asAnyRef), rs => {
      readReviewTask(rs)
    })
  }


  override def loadReviewTasksAboutUser(userId: UserId, limit: Int, orderBy: OrderBy)
        : Seq[ReviewTask] = {
    val desc = orderBy.isDescending ? "desc" | ""
    val query = i"""
      select * from review_tasks3 where site_id = ? and user_id = ?
      order by created_at $desc, id $desc limit ?
      """
    runQueryFindMany(query, List(siteId.asAnyRef, userId.asAnyRef, limit.asAnyRef), rs => {
      readReviewTask(rs)
    })
  }


  def loadReviewTasksAboutPostIds(postIds: Iterable[PostId]): immutable.Seq[ReviewTask] = {
    if (postIds.isEmpty) return Nil
    val query = i"""
      select * from review_tasks3
      where site_id = ?
        and post_id in (${ makeInListFor(postIds) })
      """
    runQueryFindMany(query, siteId.asAnyRef :: postIds.toList.map(_.asAnyRef), rs => {
      val task = readReviewTask(rs)
      dieIf(task.postId.isEmpty, "EdE2KTP8V")
      task
    })
  }


  override def loadReviewTaskCounts(isAdmin: Boolean): ReviewTaskCounts = {
    val urgentBits = ReviewReason.PostFlagged.toInt // + ... later if more urgent tasks
    val query = i"""
      select
        (select count(1) from review_tasks3
          where site_id = ? and reasons & $urgentBits != 0 and resolution is null) num_urgent,
        (select count(1) from review_tasks3
          where site_id = ? and reasons & $urgentBits = 0 and resolution is null) num_other
      """
    runQueryFindExactlyOne(query, List(siteId.asAnyRef, siteId.asAnyRef), rs => {
      ReviewTaskCounts(rs.getInt("num_urgent"), rs.getInt("num_other"))
    })
  }


  private def readReviewTask(rs: js.ResultSet): ReviewTask = {
    ReviewTask(
      id = rs.getInt("id"),
      reasons = ReviewReason.fromLong(rs.getLong("reasons")),
      createdById = rs.getInt("created_by_id"),
      createdAt = getDate(rs, "created_at"),
      createdAtRevNr = getOptionalInt(rs, "created_at_rev_nr"),
      moreReasonsAt = getOptionalDate(rs, "more_reasons_at"),
      completedAt = getOptionalDate(rs, "completed_at"),
      completedAtRevNr = getOptionalInt(rs, "completed_at_rev_nr"),
      completedById = getOptionalInt(rs, "completed_by_id"),
      invalidatedAt = getOptionalDate(rs, "invalidated_at"),
      resolution = getOptionalInt(rs, "resolution").map(new ReviewTaskResolution(_)),
      maybeBadUserId = getOptionalInt(rs, "user_id").getOrElse(UnknownUserId),
      pageId = Option(rs.getString("page_id")),
      postId = getOptionalInt(rs, "post_id"),
      postNr = getOptionalInt(rs, "post_nr"))
  }

}
