/**
 * Copyright (c) 2011 Kaj Magnus Lindberg (born 1979)
 */

package com.debiki.v0

import com.debiki.v0.PagePath._
import com.debiki.v0.Dao._
import com.debiki.v0.EmailNotfPrefs.EmailNotfPrefs
import _root_.scala.xml.{NodeSeq, Text}
import _root_.java.{util => ju, io => jio}
import _root_.com.debiki.v0.Prelude._
import java.{sql => js}
import scala.collection.{mutable => mut}
import RelDb._
import RelDbUtil._
import collection.mutable.StringBuilder


class RelDbTenantDaoSpi(val quotaConsumers: QuotaConsumers,
        val systemDaoSpi: RelDbSystemDaoSpi)
   extends TenantDaoSpi {
  // COULD serialize access, per page?

  def tenantId = quotaConsumers.tenantId

  def db = systemDaoSpi.db


  def createPage(where: PagePath, debatePerhapsGuid: Debate): Debate = {
    var debate = if (debatePerhapsGuid.guid != "?") {
      unimplemented
      // Could use debatePerhapsGuid, instead of generatinig a new guid,
      // but i have to test that this works. But should where.guid
      // be Some or None? Would Some be the guid to reuse, or would Some
      // indicate that the page already exists, an error!?
    } else {
      debatePerhapsGuid.copy(guid = nextRandomString)  // TODO use the same
                                              // method in all DAO modules!
    }
    db.transaction { implicit connection =>
      require(where.tenantId == tenantId)
      // SHOULD throw a recognizable exception on e.g. dupl page slug violation.
      _createPage(where, debate)
      val postsWithIds = _insert(debate.guid, debate.posts)
      debate.copy(posts = postsWithIds)
    }
  }


  def moveRenamePage(pageId: String,
        newFolder: Option[String], showId: Option[Boolean],
        newSlug: Option[String]): PagePath = {
    db.transaction { implicit connection =>
      _updatePage(pageId, newFolder = newFolder, showId = showId,
         newSlug = newSlug)
    }
  }


  override def saveLogin(loginReq: LoginRequest): LoginGrant = {

    // Assigns an id to `loginNoId', saves it and returns it (with id).
    def _saveLogin(loginNoId: Login, identityWithId: Identity)
                  (implicit connection: js.Connection): Login = {
      // Create a new _LOGINS row, pointing to identityWithId.
      val loginSno = db.nextSeqNo("DW1_LOGINS_SNO")
      val login = loginNoId.copy(id = loginSno.toString,
                                identityId = identityWithId.id)
      val identityType = identityWithId match {
        case _: IdentitySimple => "Simple"
        case _: IdentityOpenId => "OpenID"
        case _: IdentityEmailId => "EmailID"
        case _ => assErr("DwE3k2r21K5")
      }
      db.update("""
          insert into DW1_LOGINS(
              SNO, TENANT, PREV_LOGIN, ID_TYPE, ID_SNO,
              LOGIN_IP, LOGIN_TIME)
          values (?, ?, ?,
              '"""+
              // Don't bind identityType, that'd only make it harder for
              // the optimizer.
              identityType +"', ?, ?, ?)",
          List(loginSno.asInstanceOf[AnyRef], tenantId,
              e2n(login.prevLoginId),  // UNTESTED unless empty
              login.identityId, login.ip, login.date))
      login
    }

    def _loginWithEmailId(emailId: String): LoginGrant = {
      val (email: EmailSent, notf: NotfOfPageAction) = (
         loadEmailById(emailId = emailId),
         loadNotfByEmailId(emailId = emailId)
         ) match {
        case (Some(email), Some(notf)) => (email, notf)
        case (None, _) =>
          throw EmailNotFoundException(emailId)
        case (_, None) =>
          runErr("DwE87XIE3", "Notification missing for email id: "+ emailId)
      }
      val user = _loadUser(notf.recipientUserId) match {
        case Some(user) => user
        case None =>
          runErr("DwE2XKw5", "User `"+ notf.recipientUserId +"' not found"+
             " when logging in with email id `"+ emailId +"'.")
      }
      val idtyWithId = IdentityEmailId(id = emailId, userId = user.id,
        emailSent = Some(email), notf = Some(notf))
      val loginWithId = db.transaction { implicit connection =>
        _saveLogin(loginReq.login, idtyWithId)
      }
      LoginGrant(loginWithId, idtyWithId, user, isNewIdentity = false,
         isNewRole = false)
    }

    def _loginUnauIdty(idtySmpl: IdentitySimple): LoginGrant = {
      db.transaction { implicit connection =>
        var idtyId = ""
        var emailNotfsStr = ""
        var createdNewIdty = false
        for (i <- 1 to 2 if idtyId.isEmpty) {
          db.query("""
            select i.SNO, e.EMAIL_NOTFS from DW1_IDS_SIMPLE i
              left join DW1_IDS_SIMPLE_EMAIL e
              on i.EMAIL = e.EMAIL and e.VERSION = 'C'
            where i.NAME = ? and i.EMAIL = ? and i.LOCATION = ? and
                  i.WEBSITE = ?
            """,
            List(e2d(idtySmpl.name), e2d(idtySmpl.email),
              e2d(idtySmpl.location), e2d(idtySmpl.website)),
            rs => {
              if (rs.next) {
                idtyId = rs.getString("SNO")
                emailNotfsStr = rs.getString("EMAIL_NOTFS")
              }
            })
          if (idtyId isEmpty) {
            // Create simple user info.
            // There is a unique constraint on NAME, EMAIL, LOCATION,
            // WEBSITE, so this insert might fail (if another thread does
            // the insert, just before). Should it fail, the above `select'
            // is run again and finds the row inserted by the other thread.
            // Could avoid logging any error though!
            createdNewIdty = true
            db.update("""
              insert into DW1_IDS_SIMPLE(
                  SNO, NAME, EMAIL, LOCATION, WEBSITE)
              values (nextval('DW1_IDS_SNO'), ?, ?, ?, ?)""",
              List(idtySmpl.name, e2d(idtySmpl.email),
                e2d(idtySmpl.location), e2d(idtySmpl.website)))
            // COULD fix: returning SNO into ?""", saves 1 roundtrip.
            // Loop one more lap to read SNO.
          }
        }
        assErrIf3(idtyId.isEmpty, "DwE3kRhk20")
        val notfPrefs: EmailNotfPrefs = _toEmailNotfs(emailNotfsStr)
        // Derive a temporary user from the identity, see
        // Debiki for Developers #9xdF21.
        val user = _dummyUserFor(identity = idtySmpl,
          emailNotfPrefs = notfPrefs, id = _dummyUserIdFor(idtyId))
        val identityWithId = idtySmpl.copy(id = idtyId, userId = user.id)
        // Quota already consumed (in the `for` loop above).
        val loginWithId = _saveLogin(loginReq.login, identityWithId)
        LoginGrant(loginWithId, identityWithId, user,
           isNewIdentity = createdNewIdty, isNewRole = false)
      }
    }

    // Handle guest/unauthenticated login and email login.
    loginReq.identity match {
      case idtySmpl: IdentitySimple =>
        val loginGrant = _loginUnauIdty(idtySmpl)
        return loginGrant

      case idty: IdentityEmailId =>
        val loginGrant = _loginWithEmailId(idty.id)
        return loginGrant

      case _ => ()
    }

    db.transaction { implicit connection =>

      // Load any matching Identity and the related User.
      val (identityInDb: Option[Identity], userInDb: Option[User]) =
         _loadIdtyDetailsAndUser(forIdentity = loginReq.identity)

      // Create user if absent.
      val user = userInDb match {
        case Some(u) => u
        case None =>
          // Copy identity name/email/etc fields to the new role.
          // Data in DW1_USERS has precedence over data in the DW1_IDS_*
          // tables, see Debiki for Developers #3bkqz5.
          val idty = loginReq.identity
          val userNoId =  User(id = "?", displayName = idty.displayName,
             email = idty.email, emailNotfPrefs = EmailNotfPrefs.Unspecified,
             country = "", website = "", isSuperAdmin = false, isOwner = false)
          val userWithId = _insertUser(tenantId, userNoId)
          userWithId
      }

      // Create or update the OpenID/Twitter/etc identity.
      //
      // (It's absent, if this is the first time the user logs in.
      // It needs to be updated, if the user has changed e.g. her
      // OpenID name or email. Or Facebook name or email.)
      //
      // (Concerning simultaneous inserts/updates by different threads or
      // server nodes: This insert might result in a unique key violation
      // error. Simply let the error propagate and the login fail.
      // This login was supposedly initiated by a human, and there is
      // no point in allowing exactly simultaneous logins by one
      // single human.)

      val identity = (identityInDb, loginReq.identity) match {
        case (None, newNoId: IdentityOpenId) =>
          _insertIdentity(tenantId, newNoId.copy(userId = user.id))
        case (Some(old: IdentityOpenId), newNoId: IdentityOpenId) =>
          val nev = newNoId.copy(id = old.id, userId = user.id)
          if (nev != old) {
            if (nev.isGoogleLogin)
              assErrIf(nev.email != old.email || !old.isGoogleLogin, "DwE3Bz6")
            else
              assErrIf(nev.oidClaimedId != old.oidClaimedId, "DwE73YQ2")
            _updateIdentity(nev)
          }
          nev
        case (_, _: IdentitySimple) => assErr("DwE83209qk12")
        case (_, _: IdentityEmailId) => assErr("DwE83kIR31")
      }

      val login = _saveLogin(loginReq.login, identity)

      LoginGrant(login, identity, user, isNewIdentity = identityInDb.isEmpty,
         isNewRole = userInDb.isEmpty)
    }
  }


  private def _insertUser(tenantId: String, userNoId: User)
        (implicit connection: js.Connection): User = {
    val userSno = db.nextSeqNo("DW1_USERS_SNO")
    val user = userNoId.copy(id = userSno.toString)
    db.update("""
        insert into DW1_USERS(
            TENANT, SNO, DISPLAY_NAME, EMAIL, COUNTRY,
            SUPERADMIN, IS_OWNER)
        values (?, ?, ?, ?, ?, ?, ?)""",
        List[AnyRef](tenantId, user.id, e2n(user.displayName),
           e2n(user.email), e2n(user.country),
           tOrNull(user.isSuperAdmin), tOrNull(user.isOwner)))
    user
  }


  private def _insertIdentity(tenantId: String, idtyNoId: Identity)
        (implicit connection: js.Connection): Identity = {
    val newIdentityId = db.nextSeqNo("DW1_IDS_SNO").toString
    idtyNoId match {
      case oidIdtyNoId: IdentityOpenId =>
        val idty = oidIdtyNoId.copy(id = newIdentityId)
        db.update("""
            insert into DW1_IDS_OPENID(
                SNO, TENANT, USR, USR_ORIG, OID_CLAIMED_ID, OID_OP_LOCAL_ID,
                OID_REALM, OID_ENDPOINT, OID_VERSION,
                FIRST_NAME, EMAIL, COUNTRY)
            values (
                ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            List[AnyRef](idty.id, tenantId, idty.userId, idty.userId,
              idty.oidClaimedId, e2d(idty.oidOpLocalId), e2d(idty.oidRealm),
              e2d(idty.oidEndpoint), e2d(idty.oidVersion),
              e2d(idty.firstName), e2d(idty.email), e2d(idty.country)))
        idty
      case _ =>
        assErr("DwE03IJL2")
    }
  }


  private def _updateIdentity(identity: IdentityOpenId)
        (implicit connection: js.Connection) {
    db.update("""
      update DW1_IDS_OPENID set
          USR = ?, OID_CLAIMED_ID = ?,
          OID_OP_LOCAL_ID = ?, OID_REALM = ?,
          OID_ENDPOINT = ?, OID_VERSION = ?,
          FIRST_NAME = ?, EMAIL = ?, COUNTRY = ?
      where SNO = ? and TENANT = ?
      """,
      List[AnyRef](identity.userId, identity.oidClaimedId,
        e2d(identity.oidOpLocalId), e2d(identity.oidRealm),
        e2d(identity.oidEndpoint), e2d(identity.oidVersion),
        e2d(identity.firstName), e2d(identity.email), e2d(identity.country),
        identity.id, tenantId))
  }


  override def saveLogout(loginId: String, logoutIp: String) {
    db.transaction { implicit connection =>
      db.update("""
          update DW1_LOGINS set LOGOUT_IP = ?, LOGOUT_TIME = ?
          where SNO = ?""", List(logoutIp, new ju.Date, loginId)) match {
        case 1 => ()
        case x => assErr("DwE0kSRIE3", "Updated "+ x +" rows")
      }
    }
  }


  private def _loadLoginById(loginId: String)
        (implicit connection: js.Connection): Option[Login] = {
    val logins = _loadLogins(byLoginId = loginId)
    assErrIf(logins.length > 1, "DwE47IB6")
    logins.headOption
  }


  private def _loadLogins(byLoginId: String = null, onPageGuid: String = null)
        (implicit connection: js.Connection): List[Login] = {

    assert((byLoginId ne null) ^ (onPageGuid ne null))

    val selectList = """
        select
            l.SNO LOGIN_SNO, l.PREV_LOGIN,
            l.ID_TYPE, l.ID_SNO,
            l.LOGIN_IP, l.LOGIN_TIME,
            l.LOGOUT_IP, l.LOGOUT_TIME
        """

    val (fromWhereClause, pageOrLoginId) =
      if (onPageGuid ne null)
        ("""from DW1_PAGE_ACTIONS a, DW1_LOGINS l
          where a.TENANT = ?
            and a.PAGE_ID = ?
            and l.TENANT = a.TENANT
            and l.SNO = a.LOGIN""", onPageGuid)
      else
        ("""from DW1_LOGINS l
          where l.TENANT = ?
            and l.SNO = ?""", byLoginId)

    var logins = List[Login]()

    db.queryAtnms(selectList + fromWhereClause,
        List[AnyRef](tenantId, pageOrLoginId), rs => {
      while (rs.next) {
        val loginId = rs.getLong("LOGIN_SNO").toString
        val prevLogin = Option(rs.getLong("PREV_LOGIN")).map(_.toString)
        val ip = rs.getString("LOGIN_IP")
        val date = ts2d(rs.getTimestamp("LOGIN_TIME"))
        // ID_TYPE need not be remembered, since each ID_SNO value
        // is unique over all DW1_LOGIN_OPENID/SIMPLE/... tables.
        // (So you'd find the appropriate IdentitySimple/OpenId by doing
        // People.identities.find(_.id = x).)
        val idId = rs.getLong("ID_SNO").toString
        logins ::= Login(id = loginId, prevLoginId = prevLogin, ip = ip,
          date = date, identityId = idId)
      }
    })

    logins
  }


  def loadIdtyDetailsAndUser(forLoginId: String = null,
        forIdentity: Identity = null): Option[(Identity, User)] = {
    db.withConnection(implicit connection => {
      _loadIdtyDetailsAndUser(forLoginId = forLoginId,
          forIdentity = forIdentity) match {
        case (None, None) => None
        case (Some(idty), Some(user)) => Some(idty, user)
        case (None, user) => assErr("DwE257IV2")
        case (idty, None) => assErr("DwE6BZl42")
      }
    })
  }


  // COULD return Option(identity, user) instead of (Opt(id), Opt(user)).
  private def _loadIdtyDetailsAndUser(forLoginId: String = null,
        forIdentity: Identity = null)(implicit connection: js.Connection)
        : (Option[Identity], Option[User]) = {

    assert((forIdentity eq null) ^ (forLoginId eq null))
    assert((forIdentity eq null) || forIdentity.isInstanceOf[IdentityOpenId])

    val identityOpt = Option(forIdentity)
    val loginOpt: Option[Login] =
      if (forLoginId eq null) None
      else Some(_loadLoginById(forLoginId) getOrElse {
        return None -> None
      })

    var sqlSelectFrom = """
        select
            """+ _UserSelectListItems +""",
            i.SNO i_id,
            i.OID_CLAIMED_ID,
            i.OID_OP_LOCAL_ID,
            i.OID_REALM,
            i.OID_ENDPOINT,
            i.OID_VERSION,
            i.FIRST_NAME i_first_name,
            i.EMAIL i_email,
            i.COUNTRY i_country
          from DW1_IDS_OPENID i inner join DW1_USERS u
            on i.TENANT = u.TENANT
            and i.USR = u.SNO
          """

    val (whereClause, bindVals) = (identityOpt, loginOpt) match {
      case (None, Some(login: Login)) =>
        ("""where i.TENANT = ? and i.SNO = ?""",
           List(tenantId, login.identityId))

      case (Some(oid: IdentityOpenId), None) =>
        // With Google OpenID, the identifier varies by realm. So use email
        // address instead. (With Google OpenID, the email address can be
        // trusted — this is not the case, however, in general.
        // See: http://blog.stackoverflow.com/2010/04/openid-one-year-later/
        // Quote:
        //  "If we have an email address from a verified OpenID email
        //   provider (that is, an OpenID from a large email service we trust,
        //   like Google or Yahoo), then it’s guaranteed to be a globally
        //   unique string.")
        val (claimedIdOrEmailCheck, idOrEmail) = {
          // SECURITY why can I trust the OpenID provider to specify
          // the correct endpoint? What if Mallory's provider replies
          // with Googles endpoint? I guess the Relying Party impl doesn't
          // allow this but anyway, I'd like to know for sure.
          if (oid.isGoogleLogin)
            ("i.OID_ENDPOINT = '"+ IdentityOpenId.GoogleEndpoint +
               "' and i.EMAIL = ?", oid.email)
          else
            ("i.OID_CLAIMED_ID = ?", oid.oidClaimedId)
        }
        ("""where i.TENANT = ?
            and """+ claimedIdOrEmailCheck +"""
          """, List(tenantId, idOrEmail))

      case _ => assErr("DwE98239k2a2")
    }

    db.query(sqlSelectFrom + whereClause, bindVals, rs => {
      if (!rs.next)
        return None -> None

      // Warning: Some dupl code in _loadIdtysAndUsers:
      // COULD break out construction of Identity to reusable
      // functions.

      val userInDb = _User(rs)
      val identityInDb =
        IdentityOpenId(
            id = rs.getLong("i_id").toString,
            userId = userInDb.id,
            // COULD use d2e here, or n2e if I store Null instead of '-'.
            oidEndpoint = rs.getString("OID_ENDPOINT"),
            oidVersion = rs.getString("OID_VERSION"),
            oidRealm = rs.getString("OID_REALM"),
            oidClaimedId = rs.getString("OID_CLAIMED_ID"),
            oidOpLocalId = rs.getString("OID_OP_LOCAL_ID"),
            firstName = rs.getString("i_first_name"),
            email = rs.getString("i_email"),
            country = rs.getString("i_country"))

      assErrIf(rs.next, "DwE53IK24", "More that one matching OpenID "+
         "identity, when looking up identity: "+ identityOpt +
         ", login: "+ loginOpt)

      Some(identityInDb) -> Some(userInDb)
    })
  }


  def loadIdtyAndUser(forLoginId: String): Option[(Identity, User)] = {
    def loginInfo = "login id "+ safed(forLoginId) +
          ", tenant "+ safed(tenantId)

    _loadIdtysAndUsers(forLoginId = forLoginId) match {
      case (List(i: Identity), List(u: User)) => Some(i, u)
      case (List(i: Identity), Nil) => assErr(
        "DwE6349krq20", "Found no user for "+ loginInfo +
            ", with identity "+ safed(i.id))
      case (Nil, Nil) =>
        // The webapp should never try to load non existing identities?
        // (The login id was once fetched from the database.
        // It is sent to the client in a signed cookie so it cannot be
        // tampered with.) Identities cannot be deleted!
        // This might happen however, if a server is restarted and switches
        // over to another database, where the login id does not exist, and
        // the server continues using the same signed cookie salt.
        // -- The server could do that if a failover happens to a standby
        // database, and a few transactions were lost when the master died?!
        // COULD throw an exception and let the HTTP module delete the
        // dwCoSid cookie.
        error("Found no identity for "+ loginInfo +". Has the server" +
            " connected to a standby database? Please delete your " +
            " session cookie, \"dwCoSid\". [error DwE0921kxa13]")
      case (is, us) =>
        // There should be exactly one identity per login, and at most
        // one user per identity.
        assErr("DwE42RxkW1", "Found "+ is.length +" identities and "+
              us.length +" users for "+ loginInfo)
    }
  }


  private def _loadIdtysAndUsers(onPageWithId: String = null,
                         forLoginId: String = null
                            ): Pair[List[Identity], List[User]] = {
    // Load users. First find all relevant identities, by joining
    // DW1_PAGE_ACTIONS and _LOGINS. Then all user ids, by joining
    // the result with _IDS_SIMPLE and _IDS_OPENID. Then load the users.

    val (selectLoginIds, args) = (onPageWithId, forLoginId) match {
      // (Need to specify tenant id here, and when selecting from DW1_USERS,
      // because there's no foreign key from DW1_LOGINS to DW1_IDS_<type>.)
      case (null, loginId) => ("""
          select ID_SNO, ID_TYPE
              from DW1_LOGINS
              where SNO = ? and TENANT = ?
          """, List(loginId, tenantId))
      case (pageId, null) => ("""
          select distinct l.ID_SNO, l.ID_TYPE
              from DW1_PAGE_ACTIONS a, DW1_LOGINS l
              where a.PAGE_ID = ? and a.TENANT = ?
                and a.LOGIN = l.SNO and a.TENANT = l.TENANT
          """, List(pageId, tenantId))
      case (a, b) => illArgErr(
          "DwE0kEF3", "onPageWithId: "+ safed(a) +", forLoginId: "+ safed(b))
    }

    // Load identities and users. Details: First find identities of all types
    // by joining logins with each identity table, and then taking the union
    // of all these joins. Use generic column names (since each identity
    // table has identity provider specific column names).
    // Then join all the identities found with DW1_USERS.
    // Note: There are identities with no matching users (IdentitySimple),
    // so do a left outer join.
    // Note: There might be > 1 identity per user (if a user has merged
    // e.g. her Twitter and Facebook identities to one single user account).
    // So each user might be returned > 1 times, i.e. once per identity.
    // This wastes some bandwidth, but I guess it's better than doing a
    // separate query to fetch all relevant users exactly once -- that
    // additional roundtrip to the database would probably be more expensive;
    // I guess fairly few users will merge their identities.
    db.queryAtnms("""
        with logins as ("""+ selectLoginIds +"""),
        identities as (
            -- Simple identities
            select ID_TYPE, s.SNO I_ID, '' I_USR,
                   s.NAME I_NAME, s.EMAIL I_EMAIL,
                   e.EMAIL_NOTFS I_EMAIL_NOTFS,
                   s.LOCATION I_WHERE, s.WEBSITE I_WEBSITE
            from logins, DW1_IDS_SIMPLE s
              left join DW1_IDS_SIMPLE_EMAIL e
              on s.EMAIL = e.EMAIL and e.VERSION = 'C'
            where s.SNO = logins.ID_SNO and logins.ID_TYPE = 'Simple'
            union
            -- OpenID
            select ID_TYPE, oi.SNO I_ID, oi.USR,
                   oi.FIRST_NAME I_NAME, oi.EMAIL I_EMAIL,
                   null as I_EMAIL_NOTFS,
                   oi.COUNTRY I_WHERE, cast('' as varchar(100)) I_WEBSITE
            from DW1_IDS_OPENID oi, logins
            where oi.SNO = logins.ID_SNO and logins.ID_TYPE = 'OpenID'
            -- union
            -- Twitter tables
            -- Facebook tables
            -- Email identities (skip for now, only used when unsubscribing)
            )
        select i.ID_TYPE, i.I_ID,
            i.I_NAME, i.I_EMAIL,
            case
              -- Can only be non-null for IdentitySimple.
              when i.I_EMAIL_NOTFS is not null then i.I_EMAIL_NOTFS
              else u.EMAIL_NOTFS end  -- might be null
              u_email_notfs,
            i.I_WHERE, i.I_WEBSITE,
            u.SNO u_id,
            u.DISPLAY_NAME u_disp_name,
            u.EMAIL u_email,
            u.COUNTRY u_country,
            u.WEBSITE u_website,
            u.SUPERADMIN u_superadmin,
            u.IS_OWNER u_is_owner
        from identities i left join DW1_USERS u on
              u.SNO = i.I_USR and
              u.TENANT = ?
        """, args ::: List(tenantId), rs => {
      var usersById = mut.HashMap[String, User]()
      var identities = List[Identity]()
      while (rs.next) {
        val idId = rs.getString("I_ID")
        var userId = rs.getLong("u_id").toString  // 0 if null
        var user: Option[User] = None
        assErrIf3(idId isEmpty, "DwE392Qvc89")

        // Warning: Some dupl code in _loadIdtyDetailsAndUser:
        // COULD break out construction of Identity to reusable
        // functions.

        identities ::= (rs.getString("ID_TYPE") match {
          case "Simple" =>
            userId = _dummyUserIdFor(idId)
            val emailPrefs = _toEmailNotfs(rs.getString("u_email_notfs"))
            val i = IdentitySimple(
                id = idId,
                userId = userId,
                name = d2e(rs.getString("I_NAME")),
                email = d2e(rs.getString("I_EMAIL")),
                location = d2e(rs.getString("I_WHERE")),
                website = d2e(rs.getString("I_WEBSITE")))
            user = Some(_dummyUserFor(i, emailNotfPrefs = emailPrefs))
            i
          case "OpenID" =>
            assErrIf3(userId isEmpty, "DwE9V86kr8")
            IdentityOpenId(
                id = idId,
                userId = userId,
                // These uninteresting OpenID fields were never loaded.
                // COULD place them in an Option[OpenIdInfo]?
                oidEndpoint = "?",
                oidVersion = "?",
                oidRealm = "?",
                oidClaimedId = "?",
                oidOpLocalId = "?",
                firstName = n2e(rs.getString("I_NAME")),
                email = n2e(rs.getString("I_EMAIL")),
                country = n2e(rs.getString("I_WHERE")))
        })

        if (user isEmpty)
          user = Some(_User(rs))

        if (!usersById.contains(userId)) usersById(userId) = user.get
      }
      (identities, usersById.values.toList)  // silly to throw away hash map
    })
  }


  private def _loadUser(userId: String): Option[User] = {
    val usersByTenantAndId =  // SHOULD specify consumers
       systemDaoSpi.loadUsers(Map(tenantId -> (userId::Nil)))
    usersByTenantAndId.get((tenantId, userId))
  }


  override def savePageActions[T <: Action](pageGuid: String,
                                  actions: List[T]): List[T] = {
    db.transaction { implicit connection =>
      _insert(pageGuid, actions)
    }
  }


  override def loadPage(pageGuid: String): Option[Debate] =
    _loadPageAnyTenant(tenantId = tenantId, pageId = pageGuid)


  private def _loadPageAnyTenant(tenantId: String, pageId: String)
        : Option[Debate] = {
    /*
    db.transaction { implicit connection =>
      // BUG: There might be a NPE / None.get because of phantom reads.
      // Prevent phantom reads from DW1_ACTIONS. (E.g. rating tags are read
      // from DW1_RATINGS before _ACTIONS is considered, and another session
      // might insert a row into _ACTIONS just after _RATINGS was queried.)
      connection.setTransactionIsolation(
        Connection.TRANSACTION_SERIALIZABLE)
      */

    // COULD reuse connection throughout function, make it implicit in arg.
    var logins: List[Login] =
      db.withConnection { _loadLogins(onPageGuid = pageId)(_) }

    // Load identities and users.
    val (identities, users) = _loadIdtysAndUsers(onPageWithId = pageId)

    // Load rating tags.
    val ratingTags: mut.HashMap[String, List[String]] = db.queryAtnms("""
        select a.PAID, r.TAG from DW1_PAGE_ACTIONS a, DW1_PAGE_RATINGS r
        where a.TYPE = 'Rating' and a.TENANT = ? and a.PAGE_ID = ?
          and r.TENANT = a.TENANT and r.PAGE_ID = a.PAGE_ID and r.PAID = a.PAID
        order by a.PAID
        """,
      List(tenantId, pageId), rs => {
        val map = mut.HashMap[String, List[String]]()
        var tags = List[String]()
        var curPaid = ""  // current page action id

        while (rs.next) {
          val paid = rs.getString("PAID");
          val tag = rs.getString("TAG");
          if (curPaid isEmpty) curPaid = paid
          if (paid == curPaid) tags ::= tag
          else {
            // All tags found for the rating with _ACTIONS.PAID = curPaid.
            map(curPaid) = tags
            tags = tag::Nil
            curPaid = paid
          }
        }
        if (tags.nonEmpty)
          map(curPaid) = tags

        map
      })

    // Load page actions.
    // Order by TIME desc, because when the action list is constructed
    // the order is reversed again.
    db.queryAtnms("""
        select PAID, LOGIN, TIME, TYPE, RELPA,
              TEXT, MARKUP, WHEERE, NEW_IP
        from DW1_PAGE_ACTIONS
        where TENANT = ? and PAGE_ID = ?
        order by TIME desc
        """,
        List(tenantId, pageId), rs => {
      var actions = List[AnyRef]()
      while (rs.next) {
        val action = _Action(rs, ratingTags)
        actions ::= action  // this reverses above `order by TIME desc'
      }

      Some(Debate.fromActions(guid = pageId,
          logins, identities, users, actions))
    })
  }


  def loadRecentActionExcerpts(fromIp: String = null,
        byIdentity: IdentityOpenId = null, limit: Int): Seq[ViAc] = {

    require((fromIp ne null) ^ (byIdentity ne null))

    val (loginIdsWhereSql, loginIdsWhereValues) = {
      if (fromIp ne null)
          ("l.LOGIN_IP = ? and l.TENANT = ?", List(fromIp, tenantId))
      else
          ("l.ID_SNO = ? and l.ID_TYPE = 'OpenID' and l.TENANT = ?",
            List(byIdentity.id, tenantId))
    }

    val pagesById = mut.Map[String, Debate]()
    var pageIdsAndActions = List[(String, Action)]()

    // ((Concerning `distinct` in the last `select` below. Without it, [your
    // own actions on your own actions] would be selected twice,
    // because they'd match two rows in actionIds: a.PAID would match
    // (because it's your action) and a.RELPA would match (because the action
    // affected an action of yours). ))

    /*
    // ((The first `limit` below is not really needed, but it might
    // result in Postgres not loading terribly many login ids (I don't know
    // what query exec plan Postgres builds). I multiply by a small number,
    // 3, since some logins might have done 0 actions. If `limit * 3`
    // login ids have done 0 actions, then nothing will be returned by
    // this query.))
    with loginIds as (
       select TENANT, SNO from DW1_LOGINS
    """+ loginIdsWhereClause +"""
    order by LOGIN_TIME desc),
    actionIds as (
       select a.TENANT, a.PAGE_ID, a.PAID
    from DW1_PAGE_ACTIONS a inner join loginIds l
    on a.TENANT = l.TENANT and a.LOGIN = l.SNO
    limit """+ limit +""") */

    // SHOULD create index on DW1_LOGINS.LOGIN_TIME

    db.withConnection { implicit connection =>
      db.query("""
          with actionIds as (
            select a.TENANT, a.PAGE_ID, a.PAID
            from DW1_LOGINS l inner join DW1_PAGE_ACTIONS a
            on l.TENANT = a.TENANT and l.SNO = a.LOGIN
            where """+ loginIdsWhereSql +"""
            order by l.LOGIN_TIME desc
            limit """+ limit +""")
          select distinct -- se comment above
            a.PAGE_ID, a.PAID, a.LOGIN, a.TIME, a.TYPE, a.RELPA,
            a.TEXT, a.MARKUP, a.WHEERE, a.NEW_IP
          from DW1_PAGE_ACTIONS a inner join actionIds
            on a.TENANT = actionIds.TENANT
            and a.PAGE_ID = actionIds.PAGE_ID
            and (
              -- load actions by login id
              a.PAID = actionIds.PAID
              -- load actions that affected [an action by login id]
              -- (e.g. edits, flags, approvals)
              or (
                a.TYPE <> 'Post' and -- skip replies
                a.TYPE <> 'Rating' and -- skip ratings
                a.RELPA = actionIds.PAID))
          order by a.TIME desc
          """, loginIdsWhereValues, rs => {
        while (rs.next) {
          val pageId = rs.getString("PAGE_ID")
          // Skip ratings, for now: (as stated in the docs in Dao.scala)
          val action = _Action(rs, ratingTags = Map.empty.withDefaultValue(Nil))
          val page = pagesById.getOrElseUpdate(pageId, Debate.empty(pageId))
          val pageWithAction = page ++ (action::Nil)
          pagesById(pageId) = pageWithAction
          pageIdsAndActions ::= pageId -> action
        }
      })
    }

    /* COULD:
    val people = _loadPeople(whoDid = allActions)
    but then won't know to which page each Login/IdentityOpenId etc belongs.
    SHOULD have Page stop inheriting People, use delegation instead?
     */

    def debugDetails = "fromIp: "+ fromIp +", byIdentity: "+ byIdentity

    val smartActions = pageIdsAndActions map { case (pageId, action) =>
      val page = pagesById.get(pageId).getOrElse(assErr(
        "DwE9031211", "Page "+ pageId +" missing when loading recent actions, "+
        debugDetails))
      SmartAction(page, action)
    }

    smartActions
  }


  def loadTemplate(templPath: PagePath): Option[TemplateSrcHtml] = {
    // Minor bug: if template /some-page.tmpl does not exist, but there's
    // an odd page /some-page.tmpl/, then that *page* is found and
    // returned although it's probably not a template.  ? Solution:
    // TODO disallow '.' in directory names? but allow e.g. style.css,
    // scripts.js, template.tpl.

    val templ = _findCorrectPagePath(templPath) match {
      // If the template does not exist:
      case None => None
      case Some(path) =>
        _loadPageAnyTenant(path.tenantId, path.pageId.get) match {
          case Some(page) => page.body map (TemplateSrcHtml(_, templPath.path))
          // If someone deleted the template moments ago, after its
          // guid was found:
          case None => None
      }
    }
    templ
  }


  def loadTenant(): Tenant = {
    systemDaoSpi.loadTenants(List(tenantId)).head
    // Should tax quotaConsumer with 2 db IO requests: tenant + tenant hosts.
  }


  def createWebsite(name: String, address: String, ownerIp: String,
        ownerLoginId: String, ownerIdentity: IdentityOpenId, ownerRole: User)
        : Option[Tenant] = {
    try {
      db.transaction { implicit connection =>
        val newTenantNoId = Tenant(id = "?", name = name,
           creatorIp = ownerIp, creatorTenantId = tenantId,
           creatorLoginId = ownerLoginId, creatorRoleId = ownerRole.id,
           hosts = Nil)
        val newTenant = _createTenant(newTenantNoId)
        val newHost = TenantHost(address, TenantHost.RoleCanonical,
          TenantHost.HttpsNone)
        val newHostCount = _insertTenantHost(newTenant.id, newHost)
        assErrIf(newHostCount != 1, "DwE09KRF3")
        val ownerRoleAtNewWebsite = _insertUser(newTenant.id,
          ownerRole.copy(id = "?",
            isSuperAdmin = true, isOwner = true))
        val ownerIdtyAtNewWebsite = _insertIdentity(newTenant.id,
          ownerIdentity.copy(id = "?", userId = ownerRoleAtNewWebsite.id))
        Some(newTenant.copy(hosts = List(newHost)))
      }
    }
    catch {
      case ex: js.SQLException =>
        if (!isUniqueConstrViolation(ex)) throw ex
        None
    }
  }


  private def _createTenant(tenantNoId: Tenant)
        (implicit connection: js.Connection): Tenant = {
    assErrIf(tenantNoId.id != "?", "DwE91KB2")
    val tenant = tenantNoId.copy(
      id = db.nextSeqNo("DW1_TENANTS_ID").toString)
    db.update("""
        insert into DW1_TENANTS (
          ID, NAME, CREATOR_IP,
          CREATOR_TENANT_ID, CREATOR_LOGIN_ID, CREATOR_ROLE_ID)
        values (?, ?, ?, ?, ?, ?)
              """,
      List[AnyRef](tenant.id, tenant.name, tenant.creatorIp,
        tenant.creatorTenantId, tenant.creatorLoginId, tenant.creatorRoleId))
    tenant
  }


  def addTenantHost(host: TenantHost) = {
    db.transaction { implicit connection =>
      _insertTenantHost(tenantId, host)
    }
  }


  private def _insertTenantHost(tenantId: String, host: TenantHost)
        (implicit connection:  js.Connection) = {
    val cncl = host.role match {
      case TenantHost.RoleCanonical => "C"
      case TenantHost.RoleRedirect => "R"
      case TenantHost.RoleLink => "L"
      case TenantHost.RoleDuplicate => "D"
    }
    val https = host.https match {
      case TenantHost.HttpsRequired => "R"
      case TenantHost.HttpsAllowed => "A"
      case TenantHost.HttpsNone => "N"
    }
    db.update("""
        insert into DW1_TENANT_HOSTS (TENANT, HOST, CANONICAL, HTTPS)
        values (?, ?, ?, ?)
        """, List(tenantId, host.address, cncl, https))
  }


  def lookupOtherTenant(scheme: String, host: String): TenantLookup = {
    // TODO consume quota
    systemDaoSpi.lookupTenant(scheme, host)
  }


  def checkPagePath(pathToCheck: PagePath): Option[PagePath] = {
    _findCorrectPagePath(pathToCheck)
  }

  def listPagePaths(
    pageRanges: PathRanges,
    includeStatuses: List[PageStatus],
    sortBy: PageSortOrder,
    limit: Int,
    offset: Int
  ): Seq[(PagePath, PageDetails)] = {

    require(limit == Int.MaxValue) // for now
    require(offset == 0)  // for now

    val statusesToInclStr =
      includeStatuses.map(_toFlag).mkString("'", "','", "'")
    if (statusesToInclStr isEmpty)
      return Nil

    val orderByStr = sortBy match {
      case PageSortOrder.ByPath =>
        " order by PARENT_FOLDER, SHOW_ID, PAGE_SLUG"
      case PageSortOrder.ByPublTime =>
        " order by CACHED_PUBL_TIME desc"
    }

    val (pageRangeClauses, pageRangeValues) = _pageRangeToSql(pageRanges)

    var items = List[(PagePath, PageDetails)]()
    db.queryAtnms("""
        select PARENT_FOLDER,
            PAGE_ID,
            SHOW_ID,
            PAGE_SLUG,
            PAGE_STATUS,
            CACHED_TITLE,
            CACHED_PUBL_TIME,
            CACHED_SGFNT_MTIME
        from DW1_PAGE_PATHS
        where TENANT = ? and ("""+ pageRangeClauses +""" )
        and PAGE_STATUS in ("""+ statusesToInclStr +""")
        """+ orderByStr,
        tenantId :: pageRangeValues,
        rs => {
      while (rs.next) {
        val pagePath = _PagePath(rs, tenantId)
        val pageDetails = PageDetails(
          status = _toPageStatus(rs.getString("PAGE_STATUS")),
          cachedTitle =
              Option(rs.getString(("CACHED_TITLE"))),
          cachedPublTime =
              Option(rs.getTimestamp("CACHED_PUBL_TIME")).map(ts2d _),
          cachedSgfntMtime =
              Option(rs.getTimestamp("CACHED_SGFNT_MTIME")).map(ts2d _),
          cachedAuthors = Nil,  // db fields not yet created
          cachedCommentCount = 0  // db field not yet created
        )
        items ::= pagePath -> pageDetails
      }
    })
    items
  }


  def listActions(
        pageRanges: PathRanges,
        includePages: List[PageStatus],
        limit: Int,
        offset: Int): Seq[ActionLocator] = {

    require(0 <= limit)
    require(0 <= offset)
    // For now only:
    require(includePages == PageStatus.All)
    require(offset == 0)

    val (pageRangeClauses, pageRangeValues) = _pageRangeToSql(pageRanges, "p.")

    val query = """
       select p.PARENT_FOLDER, p.PAGE_ID, p.SHOW_ID, p.PAGE_SLUG,
          a.TYPE, a.PAID, a.TIME
       from
         DW1_PAGE_ACTIONS a inner join DW1_PAGE_PATHS p
         on a.TENANT = p.TENANT and a.PAGE_ID = p.PAGE_ID
       where
         a.TENANT = ? and ("""+ pageRangeClauses +""")
       order by a.TIME desc
       limit """+ limit

    val vals = tenantId :: pageRangeValues
    var actionLocators = List[ActionLocator]()

    db.queryAtnms(query, vals, rs => {
      while (rs.next) {
        val pagePath = _PagePath(rs, tenantId)
        val actnLctr = ActionLocator(
           pagePath = pagePath,
           actionId = rs.getString("PAID"),
           actionCtime = rs.getTimestamp("TIME"),
           actionType = rs.getString("TYPE"))
        actionLocators ::= actnLctr
      }
    })

    actionLocators.reverse
  }


  def loadPermsOnPage(reqInfo: RequestInfo): PermsOnPage = {
    // Currently all permissions are actually hardcoded in this function.
    // (There's no permissions db table.)

    /*
    The algorithm: (a sketch. And not yet implemented)
    lookup rules in PATHRULES:  (not implemented! paths hardcoded instead)
      if guid, try:  parentFolder / -* /   (i.e. any guid in folder)
      else, try:
        first: parentFolder / pageName /   (this particular page)
        then:  parentFolder / * /          (any page in folder)
      Then continue with the parent folders:
        first: parentsParent / parentFolderName /
        then: parentsParent / * /
      and so on with the parent's parent ...
    */

    // ?? Replace admin test with:
    // if (requeuster.memships.contains(AdminGroupId)) return PermsOnPage.All

    // Allow superadmins to do anything, e.g. create pages anywhere.
    // (Currently users can edit their own pages only.)
    if (reqInfo.user.map(_.isSuperAdmin) == Some(true))
      return PermsOnPage.All

    // Files whose name starts with '.' are hidden, only admins have access.
    if (reqInfo.pagePath.isHiddenPage)
      return PermsOnPage.None

    // People may view and use Javascript and CSS, but of course not edit it.
    if (reqInfo.pagePath.isCodePage)
      return PermsOnPage.None.copy(accessPage = true)

    // For now, hardcode rules here:
    val mayCreatePage = {
      val p = reqInfo.pagePath.path
      if (p == "/test/") true
      else if (p == "/allt/") true
      else if (p == "/forum/") true
      else if (p == "/wiki/") true
      else false
    }

    val isWiki = reqInfo.pagePath.folder == "/wiki/"

    PermsOnPage.Wiki.copy(
      createPage = mayCreatePage,
      editPage = isWiki,
      // Authenticated users can edit others' comments.
      // (In the future, the reputation system (not implemented) will make
      // them lose this ability should they misuse it.)
      editAnyReply =
            isWiki || reqInfo.user.map(_.isAuthenticated) == Some(true)
    )
  }


  def saveNotfs(notfs: Seq[NotfOfPageAction]) {
    db.transaction { implicit connection =>
      val valss: List[List[AnyRef]] = for (notf <- notfs.toList) yield List(
        tenantId, notf.ctime, notf.pageId, notf.pageTitle take 80,
        notf.recipientIdtySmplId.orNullVarchar,
        notf.recipientRoleId.orNullVarchar,
        notf.eventType.toString, notf.eventActionId,
        notf.targetActionId.orNullVarchar,
        notf.recipientActionId,
        notf.recipientUserDispName, notf.eventUserDispName,
        notf.targetUserDispName.orNullVarchar,
        if (notf.emailPending) "P" else NullVarchar,
        notf.debug.orNullVarchar)

      db.batchUpdate("""
        insert into DW1_NOTFS_PAGE_ACTIONS(
            TENANT, CTIME, PAGE_ID, PAGE_TITLE,
            RCPT_ID_SIMPLE, RCPT_ROLE_ID,
            EVENT_TYPE, EVENT_PGA, TARGET_PGA, RCPT_PGA,
            RCPT_USER_DISP_NAME, EVENT_USER_DISP_NAME, TARGET_USER_DISP_NAME,
            EMAIL_STATUS, DEBUG)
          values (
            ?, ?, ?, ?,
            ?, ?,
            ?, ?, ?, ?,
            ?, ?, ?,
            ?, ?)
        """, valss)
    }
  }


  private def _connectNotfsToEmail(
        notfs: Seq[NotfOfPageAction], emailId: Option[String],
        debug: Option[String])
        (implicit connection: js.Connection) {

    val valss: List[List[AnyRef]] =
      for (notf <- notfs.toList) yield List(
         emailId.orNullVarchar, debug.orNullVarchar,
         tenantId, notf.pageId, notf.eventActionId, notf.recipientActionId)

    db.batchUpdate("""
      update DW1_NOTFS_PAGE_ACTIONS
      set MTIME = now(), EMAIL_STATUS = null, EMAIL_SENT = ?, DEBUG = ?
      where
        TENANT = ? and PAGE_ID = ? and EVENT_PGA = ? and RCPT_PGA = ?
      """, valss)
  }


  def loadNotfsForRole(userId: String): Seq[NotfOfPageAction] = {
    val numToLoad = 50 // for now
    val notfsToMail = systemDaoSpi.loadNotfsImpl(   // SHOULD specify consumers
       numToLoad, Some(tenantId), userIdOpt = Some(userId))
    // All loaded notifications are to userId only.
    notfsToMail.notfsByTenant(tenantId)
  }


  def loadNotfByEmailId(emailId: String): Option[NotfOfPageAction] = {
    val notfsToMail =   // SHOULD specify consumers
       systemDaoSpi.loadNotfsImpl(1, Some(tenantId), emailIdOpt = Some(emailId))
    val notfs = notfsToMail.notfsByTenant(tenantId)
    assert(notfs.length <= 1)
    notfs.headOption
  }


  def skipEmailForNotfs(notfs: Seq[NotfOfPageAction], debug: String) {
    db.transaction { implicit connection =>
      _connectNotfsToEmail(notfs, emailId = None, debug = Some(debug))
    }
  }


  def saveUnsentEmailConnectToNotfs(email: EmailSent,
        notfs: Seq[NotfOfPageAction]) {
    db.transaction { implicit connection =>
      _saveUnsentEmail(email)
      _connectNotfsToEmail(notfs, Some(email.id), debug = None)
    }
  }


  def _saveUnsentEmail(email: EmailSent)(implicit connection: js.Connection) {

    require(email.failureText isEmpty)
    require(email.providerEmailId isEmpty)
    require(email.sentOn isEmpty)

    val vals = List(
      tenantId, email.id, email.sentTo, email.subject, email.bodyHtmlText)

    db.update("""
      insert into DW1_EMAILS_OUT(
        TENANT, ID, SENT_TO, SUBJECT, BODY_HTML)
      values (
        ?, ?, ?, ?, ?)
      """, vals)
  }


  def updateSentEmail(email: EmailSent) {
    db.transaction { implicit connection =>

      val sentOn = email.sentOn.map(d2ts(_)) getOrElse NullTimestamp
      // 'O' means Other, use for now.
      val failureType = email.failureText.isDefined ?
         ("O": AnyRef) | (NullVarchar: AnyRef)
      val failureTime = email.failureText.isDefined ?
         (sentOn: AnyRef) | (NullTimestamp: AnyRef)

      val vals = List(
        sentOn, email.providerEmailId.orNullVarchar,
        failureType, email.failureText.orNullVarchar, failureTime,
        tenantId, email.id)

      db.update("""
        update DW1_EMAILS_OUT
        set SENT_ON = ?, PROVIDER_EMAIL_ID = ?,
            FAILURE_TYPE = ?, FAILURE_TEXT = ?, FAILURE_TIME = ?
        where TENANT = ? and ID = ?
        """, vals)
    }
  }


  def loadEmailById(emailId: String): Option[EmailSent] = {
    val query = """
      select SENT_TO, SENT_ON, SUBJECT,
        BODY_HTML, PROVIDER_EMAIL_ID, FAILURE_TEXT
      from DW1_EMAILS_OUT
      where TENANT = ? and ID = ?
      """
    val emailOpt = db.queryAtnms(query, List(tenantId, emailId), rs => {
      var allEmails = List[EmailSent]()
      while (rs.next) {
        val email = EmailSent(
           id = emailId,
           sentTo = rs.getString("SENT_TO"),
           sentOn = Option(ts2d(rs.getTimestamp("SENT_ON"))),
           subject = rs.getString("SUBJECT"),
           bodyHtmlText = rs.getString("BODY_HTML"),
           providerEmailId = Option(rs.getString("PROVIDER_EMAIL_ID")),
           failureText = Option(rs.getString("FAILURE_TEXT")))
        allEmails = email::allEmails
      }
      assert(allEmails.length <= 1) // loaded by PK
      allEmails.headOption
    })
    emailOpt
  }


  def configRole(loginId: String, ctime: ju.Date,
                 roleId: String, emailNotfPrefs: EmailNotfPrefs) {
    // Currently auditing not implemented for the roles/users table,
    // so loginId and ctime aren't used.
    require(!roleId.startsWith("-") && !roleId.startsWith("?"))
    db.transaction { implicit connection =>
      db.update("""
          update DW1_USERS
          set EMAIL_NOTFS = ?
          where TENANT = ? and SNO = ? and
              (EMAIL_NOTFS is null or EMAIL_NOTFS <> 'F')
          """,
          List(_toFlag(emailNotfPrefs), tenantId, roleId))
    }
  }

  def configIdtySimple(loginId: String, ctime: ju.Date,
                       emailAddr: String, emailNotfPrefs: EmailNotfPrefs) {
    db.transaction { implicit connection =>
      // Mark the current row as 'O' (old) -- unless EMAIL_NOTFS is 'F'
      // (Forbidden Forever). Then leave it as is, and let the insert
      // below fail.
      // COULD check # rows updated? No, there might be no rows to update.
      db.update("""
          update DW1_IDS_SIMPLE_EMAIL
          set VERSION = 'O' -- old
          where TENANT = ? and EMAIL = ? and VERSION = 'C'
            and EMAIL_NOTFS != 'F'
          """,
          List(tenantId, emailAddr))

      // Create a new row with the desired email notification setting.
      // Or, for now, fail and throw some SQLException if EMAIL_NOTFS is 'F'
      // for this `emailAddr' -- since there'll be a primary key violation,
      // see the update statement above.
      db.update("""
          insert into DW1_IDS_SIMPLE_EMAIL (
              TENANT, LOGIN, CTIME, VERSION, EMAIL, EMAIL_NOTFS)
          values (?, ?, ?, 'C', ?, ?)
          """,
          List(tenantId, loginId, d2ts(ctime), emailAddr,
              _toFlag(emailNotfPrefs)))
    }
  }


  def lookupPagePathByPageId(pageId: String) =
    _lookupPagePathByPageId(pageId)(null)


  private def _lookupPagePathByPageId(pageId: String)
        (implicit connection: js.Connection)
        : Option[PagePath] = {
    // _findCorrectPagePath does a page id lookup, if Some(pageId)
    // is available.
    val idPath = PagePath(
      tenantId = tenantId, pageId = Some(pageId),
      folder = "/", showId = false, pageSlug = "")
    _findCorrectPagePath(idPath)
  }


  // Looks up the correct PagePath for a possibly incorrect PagePath.
  private def _findCorrectPagePath(pagePathIn: PagePath)
      (implicit connection: js.Connection = null): Option[PagePath] = {

    var query = """
        select PARENT_FOLDER, PAGE_ID, SHOW_ID, PAGE_SLUG
        from DW1_PAGE_PATHS
        where TENANT = ?
        """
    assert(pagePathIn.tenantId == tenantId)
    var binds = List(pagePathIn.tenantId)
    var maxRowsFound = 1  // there's a unique key
    pagePathIn.pageId match {
      case Some(id) =>
        query += " and PAGE_ID = ?"
        binds ::= id
      case None =>
        // SHOW_ID = 'F' means that the page guid must not be part
        // of the page url. ((So you cannot look up [a page that has its guid
        // as part of its url] by searching for its url without including
        // the guid. Had that been possible, many pages could have been found
        // since pages with different guids can have the same name.
        // Hmm, could search for all pages, as if the guid hadn't been
        // part of their name, and list all pages with matching names?))
        query += """
            and SHOW_ID = 'F'
            and (
              (PARENT_FOLDER = ? and PAGE_SLUG = ?)
            """
        binds ::= pagePathIn.folder
        binds ::= e2d(pagePathIn.pageSlug)
        // Try to correct bad URL links.
        // COULD skip (some of) the two if tests below, if action is ?newpage.
        // (Otherwise you won't be able to create a page in
        // /some/path/ if /some/path already exists.)
        if (pagePathIn.pageSlug nonEmpty) {
          // Perhaps the correct path is /folder/page/ not /folder/page.
          // Try that path too. Choose sort orter so /folder/page appears
          // first, and skip /folder/page/ if /folder/page is found.
          query += """
              or (PARENT_FOLDER = ? and PAGE_SLUG = '-')
              )
            order by length(PARENT_FOLDER) asc
            """
          binds ::= pagePathIn.folder + pagePathIn.pageSlug +"/"
          maxRowsFound = 2
        }
        else if (pagePathIn.folder.count(_ == '/') >= 2) {
          // Perhaps the correct path is /folder/page not /folder/page/.
          // But prefer /folder/page/ if both pages are found.
          query += """
              or (PARENT_FOLDER = ? and PAGE_SLUG = ?)
              )
            order by length(PARENT_FOLDER) desc
            """
          val perhapsPath = pagePathIn.folder.dropRight(1)  // drop `/'
          val lastSlash = perhapsPath.lastIndexOf("/")
          val (shorterPath, nonEmptyName) = perhapsPath.splitAt(lastSlash + 1)
          binds ::= shorterPath
          binds ::= nonEmptyName
          maxRowsFound = 2
        }
        else {
          query += ")"
        }
    }

    db.query(query, binds.reverse, rs => {
      var correctPath: Option[PagePath] = None
      if (rs.next) {
        correctPath = Some(pagePathIn.copy(  // keep pagePathIn.tenantId
            folder = rs.getString("PARENT_FOLDER"),
            pageId = Some(rs.getString("PAGE_ID")),
            showId = rs.getString("SHOW_ID") == "T",
            // If there is a root page ("serveraddr/") with no name,
            // it is stored as a single space; s2e removes such a space:
            pageSlug = d2e(rs.getString("PAGE_SLUG"))))
      }
      assert(maxRowsFound == 2 || !rs.next)
      correctPath
    })
  }


  private def _createPage[T](where: PagePath, debate: Debate)
                            (implicit conn: js.Connection) {
    db.update("""
        insert into DW1_PAGES (SNO, TENANT, GUID)
        values (nextval('DW1_PAGES_SNO'), ?, ?)
        """, List(where.tenantId, debate.guid))

    val showPageId = where.showId ? "T" | "F"

    // Create a draft, always a draft ('D') -- the user needs to
    // write something before it makes sense to publish the page.
    db.update("""
        insert into DW1_PAGE_PATHS (
          TENANT, PARENT_FOLDER, PAGE_ID, SHOW_ID, PAGE_SLUG, PAGE_STATUS)
        values (?, ?, ?, ?, ?, 'D')
        """,
        List(where.tenantId, where.folder, debate.guid, showPageId,
            e2d(where.pageSlug)))
  }


  private def _updatePage(pageId: String,
        newFolder: Option[String], showId: Option[Boolean],
        newSlug: Option[String])
        (implicit conn: js.Connection): PagePath = {

    PagePath.checkPath(tenantId = tenantId, pageId = Some(pageId),
      folder = newFolder getOrElse "/", pageSlug = newSlug getOrElse "")

    var updates = new StringBuilder
    var vals = List[AnyRef]()

    newFolder foreach (folder => {
      if (!vals.isEmpty) updates append ", "
      updates.append("PARENT_FOLDER = ?")
      vals ::= folder
    })

    showId foreach (showId => {
      if (!vals.isEmpty) updates append ", "
      updates.append("SHOW_ID = ?")
      vals ::= showId ? "T" | "F"
    })

    newSlug foreach (slug => {
      if (!vals.isEmpty) updates append ", "
      updates.append("PAGE_SLUG = ?")
      vals ::= slug
    })

    if (vals nonEmpty) {
      val allVals = (pageId::tenantId::vals).reverse
      val stmt = """
         update DW1_PAGE_PATHS
         set """+ updates +"""
         where TENANT = ? and PAGE_ID = ?
         """

      val numRowsChanged = db.update(stmt, allVals)

      assert(numRowsChanged <= 1)
      if (numRowsChanged == 0)
        throw PageNotFoundException(tenantId, pageId)
    }

    // This shouldn't fail; it's the same transaction as the update.
    val newPath = _lookupPagePathByPageId(pageId) getOrElse {
      val mess = "Page suddenly gone, id: "+ pageId
      // logger.error(mess)  LOG
      runErr("DwE093KFH3", mess)
    }

    newPath
  }


  private def _insert[T <: Action](pageId: String, actions: List[T])
        (implicit conn: js.Connection): List[T] = {

    var actionsWithIds = Debate.assignIdsTo(actions)
    for (action <- actionsWithIds) {
      // Could optimize:  (but really *not* important!)
      // Use a CallableStatement and `insert into ... returning ...'
      // to create the _ACTIONS row and read the SNO in one single roundtrip.
      // Or select many SNO:s in one single query? and then do a batch
      // insert into _ACTIONS (instead of one insert per row), and then
      // batch inserts into other tables e.g. _RATINGS.

      val insertIntoActions = """
          insert into DW1_PAGE_ACTIONS(
            LOGIN, TENANT, PAGE_ID, PAID, TIME,
            TYPE, RELPA, TEXT, MARKUP, WHEERE)
          values (?, ?, ?, ?, ?,
            ?, ?, ?, ?, ?)
          """
      // Keep in mind that Oracle converts "" to null.
      val commonVals = Nil // p.loginId::pageId::Nil
      action match {
        case p: Post =>
          db.update(insertIntoActions, commonVals:::List(
            p.loginId, tenantId, pageId, p.id, p.ctime, _toFlag(p.tyype),
            p.parent, e2n(p.text), e2n(p.markup), e2n(p.where)))
        case r: Rating =>
          db.update(insertIntoActions, commonVals:::List(
            r.loginId, tenantId, pageId, r.id, r.ctime, "Rating", r.postId,
            NullVarchar, NullVarchar, NullVarchar))
          db.batchUpdate("""
            insert into DW1_PAGE_RATINGS(TENANT, PAGE_ID, PAID, TAG)
            values (?, ?, ?, ?)
            """, r.tags.map(t => List(tenantId, pageId, r.id, t)))
        case e: Edit =>
          db.update(insertIntoActions, commonVals:::List(
            e.loginId, tenantId, pageId, e.id, e.ctime, "Edit",
            e.postId, e2n(e.text), e2n(e.newMarkup), NullVarchar))
        case a: EditApp =>
          db.update(insertIntoActions, commonVals:::List(
            a.loginId, tenantId, pageId, a.id, a.ctime, "EditApp",
            a.editId, e2n(a.result), NullVarchar, NullVarchar))
        case f: Flag =>
          db.update(insertIntoActions, commonVals:::List(
            f.loginId, tenantId, pageId, f.id, f.ctime, "Flag" + f.reason,
            f.postId, e2n(f.details), NullVarchar, NullVarchar))
        case d: Delete =>
          db.update(insertIntoActions, commonVals:::List(
            d.loginId, tenantId, pageId, d.id, d.ctime,
            "Del" + (if (d.wholeTree) "Tree" else "Post"),
            d.postId, e2n(d.reason), NullVarchar, NullVarchar))
        case x => unimplemented(
          "Saving this: "+ classNameOf(x) +" [error DwE38rkRF]")
      }
    }

    actionsWithIds
  }

}

// vim: fdm=marker et ts=2 sw=2 tw=80 fo=tcqwn list

