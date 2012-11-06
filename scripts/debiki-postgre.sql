/*

*** Read this ***

CAPITALIZE table, column and constraint names but use lowercase for SQL
keywords. Then one can easily find all occurrences of a certain table or
column name, by searching for the capitalized name, e.g. the "TIME" column.
If you, however, use lowercase names, then you will find lots of irrelevant
occurrances of "time".

Do not store the empty string. Store NULL instead.
((Reason: Oracle converts '' to NULL, so it's not possible to store
the empty string if you use Oracle. And Debiki is supposed to
support both PostgreSQL and Oracle.
Add constraints that check for the empty string -- name them
"...__C_NE" (see below).  ))

*****************

Naming standard
------------------
 "DW1_...__C" for check constraints,
        "__C_NE" check non empty (e.g.:  check (trim(COL) <> ''))
        "__C_N0" check not 0
        "__C_IN" check in (list of allowed values)
        "__C_B" check in ('T') or check in ('T', 'F')
        "__C_EQ" checks that things are equal
        "__C_LE" checks less than or equal
                  (and also GT: greater than, GE: greater or eq, etc.)
 "DW1_...__U" for unique constraints and unique indexes
 "DW1_...__R__..." for referential constraints
where "..." is "<abbreviated-table-name>_<abbreviated>_<column>_<names>".
E.g.:
  DW1_IDSMPLEML__R__LOGINS  -- a foreign key from IDS_SIMPLE_EMAIL to LOGINS
                            (column names excluded, Oracle allows 30 chars max)
  DW1_IDSMPLEML_EMAIL__C -- a check constraint on the EMAIL column
  DW1_IDSMPLEML_VERSION__U -- a unique constraint, which includes the VERSION
                              column (and it is the "interesting" column).
  DW1_USERS_TNT_NAME_EMAIL -- an index on USERS (TENANT, DISPLAY_NAME, EMAIL).

"DW0_TABLE" means a Debiki ("DW_") version 0 ("0") table named "TABLE",
When upgrading, one can copy data to new tables, i.e. DW<X>_TABLE instead of
modifying data in the current tables. Then it's almost impossible to
corrupt any existing data.
  --- and if you don't need to upgrade, then keep the DW0_TABLE tables.
  (and let any new DW<X>_TABLE refer to DW0_TABLE).

Foreign key indexes
------------------
When you add a foreign constraint, write the name of the corresponding
index in a comment to the right:
  constraint DW1_RLIBX__R__EMLOT  -- ix DW1_RLIBX_EMAILSENT
    foreign key ...

If there is no index, clarify why, e.g.:
  constraint DW1_RLIBX_TGTPGA__R__PGAS
      foreign key ...  -- no index: no deletes/upds in prnt tbl

Create a Debiki schema like so:
------------------
$ psql
=> create user debiki_test password 'apabanan454';
=> alter user debiki_test set search_path to '$user';
=> create database debiki_test owner debiki_test encoding 'UTF8';
$ psql --dbname debiki_test;
=> drop schema public;
=> create schema authorization debiki_test;

DW2_* todo:
------------------

Rename TENANTS to WEBSITES.
Rename TENANT_HOSTS to WEBSITE_ADDRESSES.

Add _ID to all references.
Rename all GUID/whateverID/e.g. PAID to only "ID"?
Rename all EMAIL to EMAIL_ADDR
DW2_PAGE_PATHS -> DW2_PAGES, remove DW1_PAGES  -- Or don't?? ...(read on)...
    ... It's actually useful to be able to config how pages are
    to be looked up? E.g. your.blog/2012/01/02/blog-post-title
                      or: your.blog/category/music/blog-post-title
DW2_PAGE_ACTIONS -> DW2_PAGE_POSTS?  PAGE_LOG?  Simply POSTS? :-)
 with PARENT_ID and TARGET_ID
TENANT -> TENANT_ID

DW1_USERS.SUPERADMIN -> DW1_ROLES.IS_ADMIN

??? TIME --> DATI (date time)?  CDATI, MDATI, PDATI (publ date time)

Use PAGE.ID not SNO.

INBOX_PAGE_ACTIONS -> INBOX_POSTS
USERS -> ROLES

DW1_PAGES -> DW1_PAGE_META

TENANT.ID >= 3 chars?

DW1_IDS_SIMPLE -> DW1_GUESTS
DW1_IDS_OPENID -> DW1_IDENTITIES

Rename constraints to DW1_TENANTS: ... no, DW1_WEBSITES
    DW1_TNT_ID__C_NE DW1_TNT_ID__C_N0 DW1_TNT_NAME__C_NE

DW1_LOGINS PK should include TENANT_ID
*/

/*
----- Reset schema

drop table DW0_VERSION;
drop table DW1_NOTFS_PAGE_ACTIONS;
drop table DW1_EMAILS_OUT;
drop table DW1_PAGE_RATINGS;
drop table DW1_PAGE_ACTIONS;
drop table DW1_PATHS;
drop table DW1_PAGES;
drop table DW1_LOGINS;
drop table DW1_IDS_SIMPLE;
drop table DW1_IDS_OPENID;
drop table DW1_USERS;
drop table DW1_TENANT_HOSTS;
drop table DW1_TENANTS;
drop sequence DW1_TENANTS_ID;
drop sequence DW1_USERS_SNO;
drop sequence DW1_LOGINS_SNO;
drop sequence DW1_IDS_SNO;
drop sequence DW1_PAGES_SNO;

*/

/* COULD rename abbreviations:
(so they would match those I use in Scala and CSS)

  PACTIONS --> PGAS

*/

----- Version

create table DW0_VERSION(
  VERSION varchar(100) constraint DW0_VERSION_VERSION__N not null
);

----- Tenants

-- Abbreviated: TNTS

create table DW1_TENANTS(
  ID varchar(32) not null,
  NAME varchar(100) not null,
  CTIME timestamp default now() not null,
  CREATOR_IP varchar(39),
  CREATOR_TENANT_ID varchar(32),
  CREATOR_LOGIN_ID varchar(32),
  CREATOR_ROLE_ID varchar(32),
  constraint DW1_TENANTS_ID__P primary key (ID),
  constraint DW1_TENANTS_NAME__U unique (NAME),
  constraint DW1_TENANTS_CREATOR__R__TNTS -- ix DW1_TENANTS_CREATORROLE
      foreign key (CREATOR_TENANT_ID)
      references DW1_TENANTS(ID) deferrable,
  -- Foreign keys for login and role ids are added later, when referree
  -- tables have been created (DW1_USERS/ROLES and DW1_LOGINS).
  constraint DW1_TNT_ID__C_NE check (trim(ID) <> ''),
  constraint DW1_TNT_ID__C_N0 check (ID <> '0'),
  constraint DW1_TNT_NAME__C_NE check (trim(NAME) <> '')
);

create index DW1_TENANTS_CREATORIP on DW1_TENANTS(CREATOR_IP);
-- Used by FK DW1_TENANTS_CREATOR__R__LOGINS (couldn't create it though)
create index DW1_TENANTS_CREATORLOGIN
    on DW1_TENANTS(CREATOR_TENANT_ID, CREATOR_LOGIN_ID);
-- Used by two FKs: to DW1_USERS, and from DW1_TENANTS to itself.
create index DW1_TENANTS_CREATORROLE
    on DW1_TENANTS(CREATOR_TENANT_ID, CREATOR_ROLE_ID);

-- The tenant id is a varchar2, although it's currently assigned to from
-- this number sequence.
create sequence DW1_TENANTS_ID start with 10;

-- Host addresses that handle requests for tenants. A tenant might be
-- reachable via many addresses (e.g. www.debiki.se and debiki.se).
-- One host is the canonical/primary host, and all other hosts currently
-- redirect to that one. (In the future, could add a column that flags that
-- a <link rel=canonical> is to be used instead of a HTTP 301 redirect.)
--
create table DW1_TENANT_HOSTS(
  TENANT varchar(32) not null,
  HOST varchar(50) not null,
  -- 'C'anonical: this host is the canonical host,
  -- 'R'edirect: this host redirects to the canonical host,
  -- 'L'ink: this host links to the canonical host via a <link rel=canonical>
  -- 'D'uplicate: this host duplicates the contents of the canonical host;
  --    it neither links nor redirects to the canonical host. Use for
  --    testing purposes only.
  CANONICAL varchar(1) not null,
  -- 'R'equired: http redirects to https
  -- 'A'llowed: https won't redirect to http, but includes a
  --    <link rel=canonical>.
  -- 'N'o: https times out or redirects to http.
  HTTPS varchar(1) default 'N' not null,
  CTIME timestamp default now() not null,
  MTIME timestamp default now() not null,
  constraint DW1_TNTHSTS__R__TENANTS  -- COULD create an FK index
      foreign key (TENANT)
      references DW1_TENANTS(ID),
  constraint DW1_TNTHSTS_HOST__U unique (HOST),
  constraint DW1_TNTHSTS_CNCL__C check (CANONICAL in ('C', 'R', 'L', 'D')),
  constraint DW1_TNTHSTS_HTTPS__C check (HTTPS in ('R', 'A', 'N'))
);

/* I made these changes later:
alter table DW1_TENANT_HOSTS alter CANONICAL set not null;
alter table DW1_TENANT_HOSTS alter CANONICAL type varchar(1);
alter table DW1_TENANT_HOSTS drop constraint DW1_TNTHSTS_CNCL__C;
update DW1_TENANT_HOSTS set CANONICAL = 'C' where CANONICAL = 'T';
update DW1_TENANT_HOSTS set CANONICAL = 'R' where CANONICAL = 'F';
alter table DW1_TENANT_HOSTS add
  constraint DW1_TNTHSTS_CNCL__C check (CANONICAL in ('C', 'R', 'L', 'D'));

alter table DW1_TENANT_HOSTS alter HTTPS set default 'N';
alter table DW1_TENANT_HOSTS alter HTTPS set not null;
alter table DW1_TENANT_HOSTS alter HTTPS type varchar(1);
alter table DW1_TENANT_HOSTS drop constraint DW1_TNTHSTS_HTTPS__C;
update DW1_TENANT_HOSTS set HTTPS = 'N';
alter table DW1_TENANT_HOSTS add constraint DW1_TNTHSTS_HTTPS__C check (
    HTTPS in ('R', 'A', 'N'));

drop index DW1_TNTHSTS_TENANT_CNCL__U;
-- Make sure there's only one canonical host per tenant.
create unique index DW1_TNTHSTS_TNT_CNCL__U on DW1_TENANT_HOSTS(TENANT)
where CANONICAL = 'C';
*/

-- Create a Local tenant that redirects 127.0.0.1 to `localhost'.
insert into DW1_TENANTS(ID, NAME) values ('1', 'Local');
insert into DW1_TENANT_HOSTS(TENANT, HOST, CANONICAL, HTTPS)
  values (1, 'localhost2:8080', 'T', 'No');
insert into DW1_TENANT_HOSTS(TENANT, HOST, CANONICAL, HTTPS)
  values (1, '127.0.0.1:8080', 'F', 'No');
-- commit; -- needs a `begin transaction'? or something?


----- Users

-- A user is identified by its SNO. You cannot trust the specified name or
-- email, not even with OpenID or OpenAuth login. This means it's *not*
-- possible to lookup a user in this table, given e.g. name and email.
-- Instead, you need to e.g. lookup DW1_IDS_OPENID.OID_CLAIMED_ID,
-- to find _OPENID.USR, which is the relevant user SNO.
-- Many _CLAIMED_ID might point to the same _USERS row, because users can
-- (not implemented) merge different OpenID accounts to one single account.
--
-- Currently only EMAIL_NOTFS is used.
-- The name/email columns are currently always null! The name/email from
-- the relevant identity (e.g. DW1_IDS_OPENID.FIRST_NAME) is used instead.
-- However, if the user manually fills in (not implemented) her user data,
-- then those values will take precedence (over the one from the
-- identity provider). -- In the future, if one has mapped
-- many identities to one single user account, the name/email/etc from
-- DW1_USERS will be used, rather than identity specific data,
-- because we wouldn't know which identity to use.
--
-- So most _USERS rows are essentially not used. However I think I'd like
-- to create those rows anyway, to reserve a user id for the identity.
-- (If the identity id was used, and a user row was later created,
-- then we'd probably stop using the identity id and use the user id instead,
-- then the id for that real world person would change, perhaps bad? So
-- reserve and use a user id right away, on identity creation.)
--
-- Versioning: In the future, I'll add CTIME + LOGIN columns,
-- and the current version of a user will then be either the most recent row,
-- or all rows merged together in chronological order, where Null has no
-- effect.

create table DW1_USERS(  -- COULD rename to DW1_ROLES, abbreviated RLS
  TENANT varchar(32)          not null,
  SNO varchar(32)             not null,  -- COULD rename to ID
  DISPLAY_NAME varchar(100),  -- currently null, always (2011-09-17)
  EMAIL varchar(100),   -- COULD rename to EMAIL_ADDR
  COUNTRY varchar(100),
  WEBSITE varchar(100),
  SUPERADMIN varchar(1),  -- SHOULD rename to IS_ADMIN
  IS_OWNER varchar(1),
  constraint DW1_USERS_TNT_SNO__P primary key (TENANT, SNO),
  constraint DW1_USERS_SUPERADM__C check (SUPERADMIN in ('T')),
  constraint DW1_USERS_ISOWNER__C_B check (IS_OWNER in ('T')),
  -- COULD rename to ..__R__TENANTS (with S)
  constraint DW1_USERS__R__TENANT  -- ix DW1_USERS_TNT_SNO__P
      foreign key (TENANT)
      references DW1_TENANTS(ID) deferrable,
  -- No unique constraint on (TENANT, DISPLAY_NAME, EMAIL, SUPERADMIN).
  -- People's emails aren't currently verified, so people can provide
  -- someone else's email. So need to allow many rows with the same email.
  constraint DW1_USERS_SNO_NOT_0__C check (SNO <> '0')
);

create sequence DW1_USERS_SNO start with 10;

-- Email notifications: R = receive, N = do Not receive, F = forbid forever
alter table DW1_USERS add column EMAIL_NOTFS varchar(1);
alter table DW1_USERS add constraint DW1_USERS_EMLNTF__C
    check (EMAIL_NOTFS in ('R', 'N', 'F'));

-- Add missing constraints
update DW1_USERS set DISPLAY_NAME = null where DISPLAY_NAME = '';
update DW1_USERS set EMAIL = null where EMAIL = '';
update DW1_USERS set COUNTRY = null where COUNTRY = '';
update DW1_USERS set WEBSITE = null where WEBSITE = '';
alter table DW1_USERS add constraint DW1_USERS_DNAME__C
    check (DISPLAY_NAME <> '');
alter table DW1_USERS add constraint DW1_USERS_EMAIL__C
    check (EMAIL like '%@%.%');
alter table DW1_USERS add constraint DW1_USERS_COUNTRY__C
    check (COUNTRY <> '');
alter table DW1_USERS add constraint DW1_USERS_WEBSITE__C
    check (WEBSITE <> '');

-- Foreign key from DW1_TENANTS to DW1_USERS / DW1_ROLES.
alter table DW1_TENANTS
  add constraint DW1_TENANTS_CREATOR__R__ROLES -- ix DW1_TENANTS_CREATORROLE
    foreign key (CREATOR_TENANT_ID, CREATOR_ROLE_ID)
    references DW1_USERS(TENANT, SNO) deferrable;


-- create index DW1_USERS_TNT_NAME_EMAIL
--  on DW1_USERS(TENANT, DISPLAY_NAME, EMAIL);



-- For usage by both _IDS_SIMPLE and _OPENID, so a given identity-SNO
-- is found in only one of _SIMPLE and _OPENID.
create sequence DW1_IDS_SNO start with 10;

-- Simple login identities (no password needed).
-- (Could rename to DW1_IDS_UNAU? (unauthenticated identities)
-- (No, rename to DW1_GUESTS? That's better since means both ID and "user"?)
-- Would that make sense? since a "dummy" user is created anyway (read on).)
-- When loaded from database, a dummy User is created, with its id
-- set to -SNO (i.e. "-" + SNO). Users with ids starting with "-"
-- are thus unauthenticated users (and don't exist in DW1_USERS).
-- If value absent, '-' is inserted -- but not '', since Oracle converts
-- '' to null, but I think it's easier to write SQL queries if I don't
-- have to take all possible combinations of null values into account.
create table DW1_IDS_SIMPLE(  -- abbreviated IDSMPL
  SNO varchar(32)         not null,  -- COULD rename to ID
  NAME varchar(100)       not null,
  -- COULD require like '%@%.%' and update all existing data ... hmm.
  EMAIL varchar(100)      not null,  -- COULD rename to EMAIL_ADDR
  LOCATION varchar(100)   not null,
  WEBSITE varchar(100)    not null,
  constraint DW1_IDSSIMPLE_SNO__P primary key (SNO),
  constraint DW1_IDSSIMPLE__U unique (NAME, EMAIL, LOCATION, WEBSITE),
  constraint DW1_IDSSIMPLE_SNO_NOT_0__C check (SNO <> '0')
);

-- (Uses sequence number from DW1_IDS_SNO.)



-- OpenID identities.
--
-- The same user might log in to different tenants.
-- So there might be many (TENANT, _CLAIMED_ID) with the same _CLAIMED_ID.
-- Each (TENANT, _CLAIMED_ID) points to a DW1_USER row.
--
-- If a user logs in with a claimed_id already present in this table,
-- but e.g. with modified name and email, the FIRST_NAME and EMAIL columns
-- are updated to store the most recent name and email attributes sent by
-- the OpenID provider. (Could create a _OPENID_HIST  history table, where old
-- values are remembered. Perhaps useful if there's some old email address
-- that someone used long ago, and now you wonder whose address was it?)
-- Another solution: Splitting _OPENID into one table with the unique
-- tenant+claimed_id and all most recent attribute values, and one table
-- with the attribute values as of the point in time of the OpenID login.
--
-- COULD save Null instead of '-' if data absent, and add not = '' constraints.
-- (Would DW1_AU_OPENID (AUthentication via OpenID) be a better name?)

create table DW1_IDS_OPENID( -- COULD rename to DW1_IDS_AU(thenticated)
  SNO varchar(32)                not null,  -- COULD rename to ID
  TENANT varchar(32)             not null,
  -- When an OpenID identity is created, a User is usually created too.
  -- It is stored in USR_ORIG. However, to allow many OpenID identities to
  -- use the same User (i.e. merge OpenID accounts, and perhaps Twitter,
  -- Facebok accounts), each _OPENID row can be remapped to another User.
  -- This is done via the USR row (which is = USR_ORIG if not remapped).
  -- (Not yet implemented though: currently USR = USR_ORIG always.)
  USR varchar(32)                not null, -- COULD rename to USER_ID
  USR_ORIG varchar(32)           not null, -- COULD rename to USER_ID_ORIG
  CDATI timestamp not null default now(),
  OID_CLAIMED_ID varchar(500)    not null, -- Google's ID hashes 200-300 long
  OID_OP_LOCAL_ID varchar(500)   not null,
  OID_REALM varchar(100)         not null,
  OID_ENDPOINT varchar(100)      not null,
  -- The version is a URL, e.g. "http://specs.openid.net/auth/2.0/server".
  OID_VERSION varchar(100)       not null,
  --OAUTH_...
  FIRST_NAME varchar(100)        not null,
  EMAIL varchar(100)             not null,  -- COULD rename to EMAIL_ADDR
  COUNTRY varchar(100)           not null,
  constraint DW1_IDSOID_SNO__P primary key (SNO),
  constraint DW1_IDSOID_TNT_OID__U
      unique (TENANT, OID_CLAIMED_ID),
  constraint DW1_IDSOID_USR_TNT__R__USERS  -- ix DW1_IDSOID_TNT_USR
      foreign key (TENANT, USR)
      references DW1_USERS(TENANT, SNO) deferrable,
  constraint DW1_IDSOID_SNO_NOT_0__C check (SNO <> '0')
);

create index DW1_IDSOID_TNT_USR on DW1_IDS_OPENID(TENANT, USR);
create index DW1_IDSOID_EMAIL on DW1_IDS_OPENID(EMAIL);

-- With Gmail, the claimed_id varies by realm, but the email is unique.
create unique index DW1_IDSOID_TNT_EMAIL__U on DW1_IDS_OPENID(TENANT, EMAIL)
  where OID_ENDPOINT = 'https://www.google.com/accounts/o8/ud';


-- (Uses sequence nunmber from DW1_IDS_SNO.)



-- DW1_LOGINS isn't named _SESSIONS because I'm not sure a login and logout
-- defines a session? Cannot a session start before you login?
create table DW1_LOGINS(  -- logins and logouts
  SNO varchar(32)            not null,  -- COULD rename to ID
  TENANT varchar(32)         not null,
  PREV_LOGIN varchar(32),
  -- COULD replace ID_TYPE/_SNO with: GUEST_ID and IDENTITY_ID and
  -- require that exactly one be non-NULL, and create foreign keys.
  -- That would have avoided a few bugs! and more future bugs too!?
  ID_TYPE varchar(10)        not null,
  ID_SNO varchar(32)         not null,
  -- COULD add a USR --> DW1_USERS/ROLES column? so there'd be no need to
  -- join w/ all DW1_IDS_<whatever> tables when looking up the user for
  -- a certain login. NO! Instead, add USER_ID to DW1_PAGE_ACTIONS,
  -- then one would load DW1_PAGE_ACTIONS and DW1_USERS only, to render a page.
  -- BETTER to add that USR/ROLE/GUEST colunmn to DW1_PAGE_ACTIONS?
  -- so won't have to join with this DW1_LOGINS table.
  -- COULD add a USR --> DW1_USERS/ROLES column, so an OpenID row can be
  -- disassociated from the related user/rolerow can be
  -- disassociated from the related user/role. Alternatively, I would mark
  -- the OpenID row as inactive ('O'ld) and optionally associate the OpenID
  -- with another user/role, by creating a 'C'urrent row, that points
  -- to that user/role.
  LOGIN_IP varchar(39)       not null,
  LOGIN_TIME timestamp       not null,
  LOGOUT_IP varchar(39),
  LOGOUT_TIME timestamp,
  constraint DW1_LOGINS_SNO__P primary key (SNO), -- SHOULD incl TENANT?
  constraint DW1_LOGINS_TNT__R__TENANTS  -- ix DW1_LOGINS_TNT
      foreign key (TENANT)
      references DW1_TENANTS(ID) deferrable,
  constraint DW1_LOGINS__R__LOGINS  -- ix DW1_LOGINS_PREVL
      foreign key (PREV_LOGIN)
      references DW1_LOGINS(SNO) deferrable,
  constraint DW1_LOGINS_IDTYPE__C -- should rename Simple --> Unau/Guest
      check (ID_TYPE in ('Simple', 'Unau', 'OpenID', 'EmailID')),
  constraint DW1_LOGINS_SNO_NOT_0__C check (SNO <> '0')
);

create index DW1_LOGINS_TNT on DW1_LOGINS(TENANT);
create index DW1_LOGINS_PREVL on DW1_LOGINS(PREV_LOGIN);

-- SHOULD create index on DW1_LOGINS.TENANT + ID_SNO (+ LOGIN_TIME desc)
-- COULD create index on DW1_LOGINS.TENANT + IP (+ LOGIN_TIME desc)

create sequence DW1_LOGINS_SNO start with 10;

-- Not currently possible since DW1_LOGINS' PK doesn't include TENANT_ID.
--alter table DW1_TENANTS
--  add constraint DW1_TENANTS_CREATOR__R__LOGINS -- ix DW1_TENANTS_CREATORLOGIN
--  foreign key (CREATOR_TENANT_ID, CREATOR_LOGIN_ID)
--  references DW1_LOGINS(TENANT, SNO) deferrable;



-- (Could rename to DW1_USERS_UNAU_EMAIL?)
create table DW1_IDS_SIMPLE_EMAIL(  -- abbreviated IDSMPLEML
  TENANT varchar(32) not null,
  -- The user (session) that added this row.
  LOGIN varchar(32),
  CTIME timestamp not null,
  -- C = current,  O = old, kept for auditing purposes.
  VERSION char(1) not null,
  -- We might actually attempt to send an email to this address,
  -- if EMAIL_NOTFS is set to 'R'eceive.
  EMAIL varchar(100) not null,  -- COULD rename to EMAIL_ADDR
  -- Email notifications: R = receive, N = do Not receive, F = forbid forever
  EMAIL_NOTFS varchar(1) not null,
  -- Is this PK unnecessary?
  constraint DW1_IDSMPLEML__P primary key (TENANT, EMAIL, CTIME),
  constraint DW1_IDSMPLEML__R__LOGINS  -- ix DW1_IDSMPLEML_LOGIN
      foreign key (LOGIN)  -- SHOULD include TENANT?
      references DW1_LOGINS(SNO),
  constraint DW1_IDSMPLEML_EMAIL__C check (EMAIL like '%@%.%'),
  constraint DW1_IDSMPLEML_VERSION__C check (VERSION in ('C', 'O')),
  constraint DW1_IDSMPLEML_NOTFS__C check (EMAIL_NOTFS in ('R', 'N', 'F'))
);

-- For each email, there's only one current setting.
create unique index DW1_IDSMPLEML_VERSION__U
  on DW1_IDS_SIMPLE_EMAIL (TENANT, EMAIL, VERSION)
  where VERSION = 'C';

-- Foregin key index.
create index DW1_IDSMPLEML_LOGIN on DW1_IDS_SIMPLE_EMAIL (LOGIN);


-- Could: create table DW1_IDS_SIMPLE_NAME, to rewrite inappropriate names,
-- e.g. rewrite to "F_ck" or "_ssh_l_".



----- Pages

-- (How do we know who created the page? The user who created
-- the root post, its page-action-id is always "1".)
-- COULD rename to DW1_PAGE_META
create table DW1_PAGES(
  SNO varchar(32)       not null,   -- COULD remove, use TENANT + ID instead
  TENANT varchar(32)    not null,
  GUID varchar(32)      not null,   -- COULD rename to ID
  ------ todo prod, done dev,test: alter table DW1_PAGES add column
  PAGE_ROLE varchar(10),
  PARENT_PAGE_ID varchar(32),
  ------ todo prod, done test,dev: alter table DW1_PAGES add column
  CDATI timestamp not null default now(),
  MDATI timestamp not null default now(),
  -- Page published if not null.
  PUBL_DATI timestamp default now(),
  ------
  constraint DW1_PAGES_SNO__P primary key (SNO),
  constraint DW1_PAGES__U unique (TENANT, GUID),
  -- COULD rename to ..__R__TENANTS (with S)
  constraint DW1_PAGES__R__TENANT  -- ix: primary key, well it SHOULD incl TNT
      foreign key (TENANT)
      references DW1_TENANTS(ID) deferrable,
  ------ todo prod, done dev,test: alter table DW1_PAGES add
  constraint DW1_PAGES_PARENTPAGE__R__PAGES -- ix: DW1_PAGES_TNT_PARENTPAGE
      foreign key (TENANT, PARENT_PAGE_ID)
      references DW1_PAGES(TENANT, GUID) deferrable,
  constraint DW1_PAGES_SNO_NOT_0__C check (SNO <> '0'),
  ------ todo prod, done dev,test: alter table DW1_PAGES add
  constraint DW1_PAGES_PAGEROLE__C_IN
      check (PAGE_ROLE in ('HP', 'BMP', 'BA', 'FMP', 'FT', 'WMP', 'WP')),
  ------ todo prod, done dev,test: alter table DW1_PAGES add
  constraint DW1_PAGES_CDATI_MDATI__C_LE check (CDATI <= MDATI),
  constraint DW1_PAGES_CDATI_PUBLDATI__C_LE check (CDATI <= PUBL_DATI)
);

create sequence DW1_PAGES_SNO start with 10;


------ todo prod, done test,dev:
update DW1_PAGES g
  set CDATI = t.CDATI,
      MDATI = t.MDATI,
      PUBL_DATI = t.CDATI
  from DW1_PAGE_PATHS t
  where t.PAGE_ID = g.GUID and t.TENANT = g.TENANT;

-- COULD be removed? It would bee needed because of a foreign key above
-- (search for the index name), *unless* Postgres is able to use
-- DW1_PAGES_TNT_PARENT_PUBLDATI instead -- but that index has some
-- may-be-Null columns, so I don't know if it is usable?
create index DW1_PAGES_TNT_PARENTPAGE
    on DW1_PAGES (TENANT, PARENT_PAGE_ID);

create index DW1_PAGES_TNT_PARENT_PUBLDATI
    on DW1_PAGES (TENANT, PARENT_PAGE_ID, PUBL_DATI);

-- So one can easily find all non-published pages.
create index DW1_PAGES_TNT_PRNT_CDATI_NOPUB
    on DW1_PAGES (TENANT, PARENT_PAGE_ID, CDATI)
    where PUBL_DATI is null;
------


----- Actions

-- Contains all posts, edits, ratings etc, everything that's needed to
-- render a discussion.
create table DW1_PAGE_ACTIONS(   -- abbreviated PGAS (PACTIONS deprectd abbrv.)
  PAGE varchar(32), -- deprecated, no longer written to
  TENANT varchar(32)  not null,
  PAGE_ID varchar(32)  not null,
  PAID varchar(32)     not null,  -- page action id  COULD rename to ID
  LOGIN varchar(32)    not null,  -- COULD rename to LOGIN_ID
  TIME timestamp       not null,  -- COULD rename to CTIME
  TYPE varchar(20)     not null,
  -- COULD split into TARGET_PGA (for edits) and PARENT_PGA (for posts)
  -- (both would be useful if moving a post to somewhere else on the page).
  RELPA varchar(32)    not null,  -- related page action
  -------
  -- (consider this carefully before doing anything!)
  -- The most recent action applied to RELPA,
  -- before this action. A unique key on -version ensures all actions
  -- are aware of everything that happened before them. This prevents
  -- a user from overwriting someone elses edits without noticing them,
  -- if two users edit the same post at the same time.
  -- Must be null for 'Post':s and ['Edit's of type 'D'raft] since it's okay
  -- with many replies (Post:s) and edit-drafts
  -- to one post. (Or use one target-version for the whole page? That
  -- seems rather restrictive.)
  -- **do NOT add this column now** -- perhap's I'll implement some kind
  -- of branching/tagging also/instead, a la Git?
  -- PARENT_VERSION / TARGET_VERSION varchar(32),
  -- ACTION_PATH_1 varchar(20) not null, -- see debiki-for-developers.txt
  -- STATUS char(1)    not null, -- check in 'D', 'S', 'P', 'E'
  -------
  TEXT text,
  MARKUP varchar(30),
  WHEERE varchar(150),
  -- Non-null if the action was automaticaly approved:
  -- 'P'reliminarily approved (e.g. a new user that we hope is well behaved)
  -- 'W'ell behaved user, therefore automatically approved.
  -- 'A'uthoritative user (e.g. an admin), therefore automatically approved.
  -- 'M'anual approval, by authoritative user
  APPROVAL varchar(1),
  -- For edits only.
  -- 'A' if the edit was applied automatically upon creation.
  -- (When you edit a post of yours, the edit is automatically applied.)
  AUTO_APPLICATION varchar(1),
  NEW_IP varchar(39), -- null, unless differs from DW1_LOGINS.START_IP
 alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_TNT_PGID_ID__P
     primary key (TENANT, PAGE_ID, PAID);
 alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_TNT_PGID__R__PGPTHS
     foreign key (TENANT, PAGE_ID)
     references DW1_PAGE_PATHS (TENANT, PAGE_ID) deferrable;
 alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS__R__PGAS
     foreign key (TENANT, PAGE_ID, RELPA) -- no ix! no dels/upds in prnt tbl
                       -- and no joins (loading whole page at once instead)
     references DW1_PAGE_ACTIONS (TENANT, PAGE_ID, PAID) deferrable;
  constraint DW1_PACTIONS__R__LOGINS  -- ix DW1_PACTIONS_LOGIN
      foreign key (LOGIN)
      references DW1_LOGINS (SNO) deferrable,
  constraint DW1_PACTIONS__R__PAGES -- deprecated. Ix DW1_PACTIONS_PAGE_PAID__P
      foreign key (PAGE)
      references DW1_PAGES (SNO) deferrable,
  -- The body and title and template etc. have ids 1, 2, 3 etc, i.e. one
  -- char only, and are their own parents.
  -- No other actions may have 1 char ids.
  -- alter table DW1_PAGE_ACTIONS add
  constraint DW1_PGAS_PAID_RELPA__C_EQ
      check ((length(PAID) = 1) = (RELPA = PAID)),
  -- The body, titles and templates are always of type 'Post'.
  constraint DW1_PGAS_TYPE__C_IS_POST
      check (case length(PAID)
        when 1 then (TYPE = 'Post')
        else true
      end),
  constraint DW1_PGAS_TYPE__C_IN check (TYPE in (
        'Post', 'Publ', 'Meta', 'Edit',
        'EditApp',
        'Aprv', 'Rjct',
        'Rating',
        'DelPost', 'DelTree',
        'FlagSpam', 'FlagIllegal', 'FlagCopyVio', 'FlagOther')),
  -- There must be no action with id 0; let 0 mean nothing.
  constraint DW1_PACTIONS_PAID_NOT_0__C
      check (PAID <> '0'),
  -- The root post has id 1 and it must be its own parent.
  constraint DW1_PACTIONS_ROOT_IS_1__C
      check (RELPA = case when PAID = '1' then '1' else RELPA end),
  -- The root post must be a 'Post'.
  constraint DW1_PACTIONS_ROOT_IS_POST__C
      check (TYPE = case when PAID = '1' then 'Post' else TYPE end),
  constraint DW1_PGAS_APPROVAL__C_IN
      check (APPROVAL in ('P', 'W', 'A', 'M')),
  -- Approval rows must have an approval reason defined,
  -- but rejections must have no approval reason.
  constraint DW1_PGAS_TYPE_APPROVAL__C check(
      case TYPE
        when 'Aprv' then (APPROVAL is not null)
        when 'Rjct' then (APPROVAL is null)
        else true
      end),
  constraint DW1_PGAS_AUTOAPPL__C_IN
      check (AUTO_APPLICATION in ('A')),
  -- Only edits can be applied.
  constraint DW1_PGAS_AUTOAPPL_TYPE__C check(
    case
      when AUTO_APPLICATION is not null then (TYPE = 'Edit')
      else true
    end),
  -- An edit that has been approved must also have been applied.
  constraint DW1_PGAS_APPR_AUTOAPPL__C check(
    case
      when APPROVAL is not null then (
        TYPE <> 'Edit' or AUTO_APPLICATION is not null)
      else true
    end)
);
  -- Cannot create an index organized table -- not available in Postgre SQL.


-- Did later:
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_PAID__C_NE
  check (trim(PAID) <> '');
update DW1_PAGE_ACTIONS set MARKUP = null where TYPE <> 'Post';
update DW1_PAGE_ACTIONS set MARKUP = 'dmd0' where TYPE = 'Post';
update DW1_PAGE_ACTIONS set TEXT = null where TEXT = '';
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_TEXT__C_NE
  check (trim(TEXT) <> '');
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_MARKUP__C_NE
  check (trim(MARKUP) <> '');
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_POST_MARKUP__C_NN
  check (
    case TYPE
      when 'Post' then (MARKUP is not null)
      else true
    end
  );
update DW1_PAGE_ACTIONS set WHEERE = null where WHEERE = '';
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_WHERE__C_NE
  check (trim(WHEERE) <> '');
update DW1_PAGE_ACTIONS set NEW_IP = null where NEW_IP = '';
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_NEWIP__C_NE
  check (trim(NEW_IP) <> '');




/* Later: (consider this carefully before doing anything!)
-- ! Don't forget to add constraint to the actual table definition (above).

-- How am I supposed to update all old action target versions?!
alter table DW1_PAGE_ACTIONS add column TARGET_VERSION varchar(32);
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_TGTVER__U
    unique (PAGE, RELPA, TARGET_VERSION);

-- Currently there's only the root post and its replies,
-- and the root post has id 1. So when ACTION_PATH is introduced,
-- all paths start with '1' (the root post).
--  But! Really, it should be named POST_PATH, because only posts
--  builds up the path (but not edits and flags etcetera?).
alter table DW1_PAGE_ACTIONS add column ACTION_PATH not null default '1';
alter table DW1_PAGE_ACTIONS add constraint DW1_PGAS_ACTIONPATH__C
      check (ACTION_PATH like '1%' or  -- page body id is '1'
             ACTION_PATH like '2%' or  -- page title id is '2'
             ACTION_PATH like '3%');   -- page template id will be '3'?
*/

-- Needs an index on LOGIN: it's an FK to DW1_LOINGS, whose END_IP/TIME is
-- updated at the end of the session.
create index DW1_PACTIONS_LOGIN on DW1_PAGE_ACTIONS(LOGIN);


-- COULD rename to DW1_ACTION_RATINGS.
-- Include PAGE_ID so all ratings on a single page can be loaded in one query.
create table DW1_PAGE_RATINGS(  -- abbreviated ARTS? PRATINGS deprctd.
  TENANT varchar(32) not null,
  PAGE_ID varchar(32) not null,
  PAGE varchar(32), -- deprecated, no longer written to
  PAID varchar(32),
  TAG varchar(30) not null,
  -- (will be dropped: done@test)
  constraint DW1_ARTS__P primary key (TENANT, PAGE_ID, PAID, TAG),
  constraint DW1_ARTS__R__PGAS  -- ix primary key
      foreign key (TENANT, PAGE_ID, PAID)
      references DW1_PAGE_ACTIONS(TENANT, PAGE_ID, PAID) deferrable
);


-- COULD create, so it'd be possible to link a Role from an Action,
-- e.g. to implement a ChangeAuthor action.
-- create table DW1_ACTIONS_TO_USERS(
--   PAGE_ID varchar(32) not null,
--   ACTION_ID varchar(32) not null,
--   IDTY_UNAU_ID varchar(32), -- FK to DW1_IDS_UNAU (DW1_IDS_SIMPLE currently)
--   ROLE_ID varchar(32))  -- FK to DW1_ROLES  (DW1_USERS currently)
-- and either IDTY_UNAU_ID or ROLE_ID is specified.


----- Emails and Inbox


create table DW1_EMAILS_OUT(  -- abbreviated EMLOT
  TENANT varchar(32) not null,
  -- A random string, perhaps 5 or 10 chars? So it cannot be guessed
  -- or very easily brute forced - it's included in the unsubscribe link.
  ID varchar(32) not null,
  SENT_TO varchar(100) not null,  -- only one recipient, for now
  SENT_ON timestamp,
  SUBJECT varchar(200) not null,
  -- (Could move to separate table, if short of storage space, so the body
  -- can be deleted, but other email info kept, without this causing table
  -- fragmentation.)
  BODY_HTML varchar(2000) not null,
  -- E.g. Amazon SES assigns their own guid to each email. Their API returns
  -- the guid when the email is sent (it's not available until then).
  -- If an email bounces, you look up the provider's email guid
  -- to find out which email bounced.
  PROVIDER_EMAIL_ID varchar(100),
  -- B/R/C/O: bounce, rejection, complaint, other.
  FAILURE_TYPE varchar(1) default null,
  -- E.g. a bounce or rejection message.
  FAILURE_TEXT varchar(2000) default null,
  FAILURE_TIME timestamp default null,
  constraint DW1_EMLOT_TNT_ID__P primary key (TENANT, ID),
  constraint DW1_EMLOT__R__TNTS  -- ix DW1_EMLOT__P
      foreign key (TENANT)
      references DW1_TENANTS (ID),
  constraint DW1_EMLOT_FAILTYPE__C check (
      FAILURE_TYPE in ('B', 'R', 'C', 'O')),
  constraint DW1_EMLOT_FAILTEXT_TYPE__C check (
      FAILURE_TEXT is null = FAILURE_TYPE is null),
  constraint DW1_EMLOT_FAILTIME_TYPE__C check (
      FAILURE_TIME is null = FAILURE_TYPE is null)
);


-- Notifications of page actions, to a role -- then RCPT_ROLE_ID is non-null --
-- or to an unauthenticated user -- then RCPT_ID_SIMPLE is non-null.
-- The table is somewhat de-normalized, for simper SQL, and performance.
create table DW1_NOTFS_PAGE_ACTIONS(   -- abbreviated NTFPGA
  ----- Where, when?
  TENANT varchar(32) not null,
  CTIME timestamp not null,
  MTIME timestamp default null,
  PAGE_ID varchar(32) not null,
  PAGE_TITLE varchar(100) not null,
  ----- To whom? To either an unauthenticated user, or a role.
  RCPT_ID_SIMPLE varchar(32),
  RCPT_ROLE_ID varchar(32),
  ----- What, why?
  -- The event page action id is the action that generated this inbox item.
  -- The target page action id is an optional action that the
  -- event-action acted upon.
  -- For example, if an admin publishes an edit of a comment of yours,
  -- then the publication would be the event, the edit would be the
  -- event target, and your comment is the recipient's page action.
  -- Another example: Someone replies to a comment of yours. Then
  -- that reply is the event, and TARGET_PGA would be null.
  -- (Perhaps TARGET_PGA can be derived from EVENT_PGA, but doing that
  -- would require > 1 query, or 1 query with rather complicated joins,
  -- when loading notifications.)
  EVENT_TYPE varchar(20) not null,
  EVENT_PGA varchar(32) not null,
  TARGET_PGA varchar(32),
  -- The page action that is the reason that the recipient is to be notified.
  -- For example, if a user has written a comment, with id X,
  -- then RCPT_PGA could be = X, and EVENT_PGA could be the id of a reply
  -- to X.
  RCPT_PGA varchar(32) not null,
  ----- Related user names
  RCPT_USER_DISP_NAME varchar(100) not null,
  EVENT_USER_DISP_NAME varchar(100) not null,
  TARGET_USER_DISP_NAME varchar(100),
  ----- State
  -- 'N' New, means: Attempt to notify the user, perhaps send email.
  -- 'O' Old means: The user has been notified. Okay to delete, or keep for
  -- auditing purposes.
  STATUS varchar(1) default 'N' not null,
  -- Email notifications
  EMAIL_STATUS varchar(1),  -- 'P'ending or Null, index on 'P'
  -- COULD rename to EMAIL_ID, because the email might actually not have
  -- been sent.
  EMAIL_SENT varchar(32) default null, -- references DW1_EMAILS_OUT.ID
  EMAIL_LINK_CLICKED timestamp default null,  -- COULD rename to ...CLICK_DATI
  -- WEB_LINK_SHOWN timestamp,
  -- WEB_LINK_CLICKED timestamp,
  DEBUG varchar(200) default null,
  --
  ----- Constraints
  -- For each action of yours, you can receive at most one notification
  -- per action by other users.
  constraint DW1_NTFPGA_T_PG_EVT_RCPT__P
      primary key (TENANT, PAGE_ID, EVENT_PGA, RCPT_PGA),
  -- (There're 2 unique indexes, and a
  -- check constraint, DW1_NTFPGA_IDSMPL_ROLE__C, that ensures one of those
  -- unique indexes is active.)
  --
  -- These 2 FKs don't exist yet, because DW1_PAGE_ACTIONS.PAGE_ID
  -- does not yet exist.
  --constraint DW1_NTFPGA_EVTPGA__R__PGAS
  --    foreign key (PAGE_ID, EVENT_PGA) -- no index: no dels/upds in prnt tbl
  --    references DW1_PAGE_ACTIONS (PAGE_ID, PAID) deferrable,
  --constraint DW1_NTFPGA_TGTPGA__R__PGAS
  --    foreign key (PAGE_ID, TARGET_PGA) -- no index: no dels/upds in prnt tbl
  --    references DW1_PAGE_ACTIONS (PAGE_ID, PAID) deferrable,
  --
  constraint DW1_NTFPGA__R__RLS  -- e.g. ix DW1_NTFPGA_TNT_ROLE_CTIME
      foreign key (TENANT, RCPT_ROLE_ID)
      references DW1_USERS (TENANT, SNO) deferrable,
  -- Not possible, because DW1_IDS_SIMPLE has no TENANT column, should I add one?
  -- constraint DW1_NTFPGA__R__IDSSMPL  -- e.g. ix DW1_NTFPGA_TNT_IDSMPL_CTIME
  --    foreign key (TENANT, RCPT_ID_SIMPLE)
  --    references DW1_IDS_SIMPLE (TENANT, SNO) deferrable,
  --
  -- Ensure exactly one of ROLE and ID_SIMPLE is specified.
  constraint DW1_NTFPGA_IDSMPL_ROLE__C check (
      (RCPT_ROLE_ID is null) <> (RCPT_ID_SIMPLE is null)),
  constraint DW1_NTFPGA_STATUS__C check (STATUS in ('N', 'O')),
  constraint DW1_NTFPGA_EMLST__C_IN check (EMAIL_STATUS = 'P'),
  constraint DW1_NTFPGA__R__EMLOT  -- ix DW1_NTFPGA_TNT_EMAILSENT
      foreign key (TENANT, EMAIL_SENT)
      references DW1_EMAILS_OUT (TENANT, ID),
  constraint  DW1_NTFPGA_EMAILCLKD__C check (
      case
        when (EMAIL_LINK_CLICKED is null) then true
        else (EMAIL_SENT is not null)
      end)
);


-- Add two unique indexes that ensure each EVENT_PGA results in at most
-- one notification to each user.
create unique index DW1_NTFPGA_TNT_RL_PG_EVT__U
    on DW1_NOTFS_PAGE_ACTIONS (TENANT, RCPT_ROLE_ID, PAGE_ID, EVENT_PGA)
    where RCPT_ROLE_ID is not null;
create unique index DW1_NTFPGA_T_IDSMPL_PG_EVT__U
    on DW1_NOTFS_PAGE_ACTIONS (TENANT, RCPT_ID_SIMPLE, PAGE_ID, EVENT_PGA)
    where RCPT_ID_SIMPLE is not null;

create index DW1_NTFPGA_TNT_ROLE_CTIME
    on DW1_NOTFS_PAGE_ACTIONS (TENANT, RCPT_ROLE_ID, CTIME);
create index DW1_NTFPGA_TNT_IDSMPL_CTIME
    on DW1_NOTFS_PAGE_ACTIONS (TENANT, RCPT_ID_SIMPLE, CTIME);
create index DW1_NTFPGA_TNT_STATUS_CTIME
    on DW1_NOTFS_PAGE_ACTIONS (TENANT, STATUS, CTIME);
create index DW1_NTFPGA_EMLPNDNG_CTIME
    on DW1_NOTFS_PAGE_ACTIONS (EMAIL_STATUS, CTIME)
    where EMAIL_STATUS = 'P';
create index DW1_NTFPGA_TNT_EMAILSENT
    on DW1_NOTFS_PAGE_ACTIONS (TENANT, EMAIL_SENT);



----- Paths (move to Pages section above?)

create table DW1_PAGE_PATHS(  -- abbreviated PGPTHS
  TENANT varchar(32) not null,
  PARENT_FOLDER varchar(100) not null,
  PAGE_ID varchar(32) not null,
  SHOW_ID varchar(1) not null,
  PAGE_SLUG varchar(100) not null,
  --
  -- SHOULD move below STATUS + CACHED_... cols to DW1_PAGES, because
  -- there migt be many paths to each page. (If you move a page,
  -- old paths should redirect to its new location). And add CANONICAL
  -- column to this table.
  --
  -- The page status, see Debiki for Developers #9vG5I.
  -- 'D'raft, 'P'ublished, 'X' deleted, 'E'rased.
  PAGE_STATUS varchar(1) not null,
  -- Should be updated whenever the page is renamed.
  CACHED_TITLE varchar(100) default null,
  -- When the page body was published (draft versions don't count).
  CACHED_PUBL_TIME timestamp default null,
  -- When the page was last modified in a significant way. Of interest
  -- to e.g. Atom feeds.
  CACHED_SGFNT_MTIME timestamp default null,
  CDATI timestamp not null default now(),
  MDATI timestamp not null default now(),
  constraint DW1_PGPTHS_TNT_PGID__P primary key (TENANT, PAGE_ID),
  constraint DW1_PGPTHS_TNT_PGID__R__PAGES  -- ix DW1_PGPTHS_TNT_PAGE__P
      foreign key (TENANT, PAGE_ID)
      references DW1_PAGES (TENANT, GUID) deferrable,
  -- Ensure the folder path contains no page ID mark; '-' means an id follows.
  constraint DW1_PGPTHS_FOLDER__C_DASH check (PARENT_FOLDER not like '%/-%'),
  constraint DW1_PGPTHS_FOLDER__C_START check (PARENT_FOLDER like '/%'),
  constraint DW1_PGPTHS_SHOWID__C_IN check (SHOW_ID in ('T', 'F')),
  constraint DW1_PGPTHS_SLUG__C_NE check (trim(PAGE_SLUG) <> ''),
  constraint DW1_PGPTHS_PGSTS__C_IN check (PAGE_STATUS in('D', 'P', 'X', 'E')),
  constraint DW1_PGPTHS_CACHEDTITLE__C_NE check (trim(CACHED_TITLE) <> ''),
  constraint DW1_PGPTHS_MTIME_PUBLTIME__C check (
      CACHED_SGFNT_MTIME >= CACHED_PUBL_TIME),
  constraint DW1_PGPTHS_CDATI_MDATI__C_LE check (CDATI <= MDATI)
);

-- COULD add index on TENANT + CDATI? -- but better to add on
-- DW1_PAGES.TENANT + CDATI / PUBL_DATI instead?

-- TODO: Test case: no 2 pages with same path, for the index below.
-- Create an index that ensures (tenant, folder, page-slug) is unique,
-- if the page guid is not included in the path to the page.
-- That is, if the path is like:  /some/folder/page-slug  (*no* guid in path)
-- rather than:  /some/folder/-<guid>-page-slug  (guid included in path)
-- then ensure there's no other page with the same /some/folder/page-slug.
create unique index DW1_PGPTHS__U
on DW1_PAGE_PATHS(TENANT, PARENT_FOLDER, PAGE_SLUG)
where SHOW_ID = 'F';

-- Also create an index that covers *all* pages (even those without the ID
-- shown before the page slug).
create index DW1_PGPTHS_ALL
on DW1_PAGE_PATHS(TENANT, PARENT_FOLDER, PAGE_SLUG, PAGE_ID);



----- Quotas

/**
 * 10 quotas roughly roughly corresponots to 1 USD. However, in this
 * table, we store microquota, that is, units of 1e-6 quota.
 *
 * Storing 1 byte costs roughly roughly 1 microquota:
 *   1 GB-month costs around $0.1 (at Amazon EC2 and Google's Cloud Storage)
 *   Assume we store 1 byte for 10 years, at 10 locations
 *   (including backups and redundancy), then:
 *   1 * $0.1/1e9 * 12 * 10 * 10 * 1e6 = 1.2 ~= 1
 * One database IO request also costs roughly
 * 1 microquota. (Amazon EC2: $0.1 per 1 million I/O requests.)
 *
 * Sending an email costs 1 000 microquota.
 *   ($0.1/1000 emails, Google AppEngine and Amazon EC2.)
 *
 * 10 quota per month (i.e. $1) should cover the costs for a popular blog,
 * (that publishes something every 3rd day, 100 comments per article, 20 000
 * page views per article, 300 emails per article.)
 *
 * Disk quota is considered consumed when you insert new data, but
 * also when you update existing data (which happens very infrequently,
 * but I think that counting quota stops certain DoS attacks).
 */
create table DW1_QUOTAS(

  ------ The quota consumer

  -- The consumer is one of:
  --  - An IP number, global, over all tenants
  --  - An IP number, per tenant
  --  - A tenant
  --  - A role (per tenant)
  --  - (An address, e.g. email addr or Twitter? Not implemented.)
  -- When quota is consumed, it's taken from the tenant, but
  -- other involved consumers have their quota updated too: their
  -- QUOTA_USED_FREELOADED is incremented. By setting a freeload limit,
  -- it's possible to prevent a single IP number from exhausting
  -- a tenant's quota. (This prevents certain over quota DoS
  -- attacks against tenants.)
  --
  -- '-' is stored instead of null, so index lookups can be made via a PK.
  -- I could have opted to use a unique functinal index instead,
  -- but why? That'd just be slightly more code and no PK?
  -- Or I could have used 4 unique partial indexes -- that'd be much more code,
  -- but those indexes would be less skewed than the current PK index.
  TENANT varchar(32),
  IP varchar(32),
  ROLE_ID varchar(32),

  ------- Version and time

  -- 'C'urrent/'O'ld. Old rows are kept for autiding/statistics gathering
  -- purposes. Constraints are less stringent, for them.
  VERSION varchar(1) not null,

  -- Creation date, for reporting (not implemented) and debugging.
  CTIME timestamp not null,

  -- The last change date influences the maximum amount of free quota left.
  -- QUOTA_DAILY_FREE/FREELOAD only grows for a few days beyond MTIME
  -- (so you won't have 5 month's worth of quota after 5 month's absence).
  MTIME timestamp not null,

  ------ Quota used

  -- A non-tenant consumer freeloads as much as possible on tenants' quotas.
  -- If it runs out of its own allowed freeload quota, or the relevant
  -- tenant gets over quota, then the consumer spends its free quota,
  -- rather than its paid quota, because the free quota might be regenerated,
  -- see QUOTA_DAILY_FREE. As a last resort, it spends its paid quota.
  -- Tenants usually spend paid quota though. A tenant is given some
  -- free quota on creation, but thereafter it usually gets no free quota.
  -- Real users freeload on the tenant, not the other way around.

  -- (Could actually replace QUOTA_USED_PAID and QUOTA_USED_FREE with
  -- quota-left, but I'm interested in how QUOTA_USED_* changes over time,
  -- and that info would be hard to derive, or impossible, if
  -- DW1_QUOTA_COSTS is changed.)

  -- Amount of quota used that the consumer has paid for.
  QUOTA_USED_PAID bigint not null default 0,

  -- Quota used that the consumer was given for free.
  QUOTA_USED_FREE bigint not null default 0,

  -- How much a quota consumer has freeloaded on other consumers' paid
  -- or free quota. E.g. a role or ip that has freeloaded on a tenant.
  QUOTA_USED_FREELOADED bigint not null default 0,

  ------ Quota limits

  -- Incremented when the consumer pays money, or by superadmins if needed
  -- for whatever reasons.
  QUOTA_LIMIT_PAID bigint not null default 0,

  -- Incremented by QUOTA_DAILY_FREE, or by superadmins.
  QUOTA_LIMIT_FREE bigint not null default 0,

  -- Set this to 0 prevents the consumer from doing anything at all,
  -- unless it pays or is given free quota.
  QUOTA_LIMIT_FREELOAD bigint not null default 0,

  ------ Quota grants

  -- For charitable organizations.
  QUOTA_DAILY_FREE bigint not null default 0,

  -- The consumer is allowed to freeload this much each day (or more,
  -- if it's allowed to accumulate over e.g. a week before being clamped).
  QUOTA_DAILY_FREELOAD bigint not null default 0,

  ------ Resource usage statistics

  -- Values are totals, since consumer creation.
  NUM_LOGINS int not null default 0,
  NUM_IDS_UNAU int not null default 0,
  NUM_IDS_AU int not null default 0,
  NUM_ROLES int not null default 0,
  NUM_PAGES int not null default 0,
  NUM_ACTIONS int not null default 0,
  NUM_ACTION_TEXT_BYTES bigint not null default 0,
  NUM_NOTFS int not null default 0,
  NUM_EMAILS_OUT int not null default 0,
  NUM_DB_REQS_READ bigint not null default 0,
  NUM_DB_REQS_WRITE bigint not null default 0,

  -- Could add:
  --  NUM_PAGE_VIEWS
  --  NUM_REQUESTS
  --  NUM_BYTES_UPLOADED
  --  NUM_BYTES_DOWNLOADED
  --  NUM_CPU_SECONDS
  -- But if you connect from a new IP, then it's not reasonable
  -- to create a new row? Perhaps start counting only when the IP
  -- has written sth to db?

  ------ Constraints

  constraint DW1_QTAS_TNT_IP_ROLE__C check (
      case
        when IP <> '-' then (
          -- This is either quota for this ip, for a certain tenant, or for
          -- this ip, globally for all tenants (then tenant is unspecified).
          ROLE_ID = '-')
        else (
          -- This is either per tenant quota, then ROLE_ID is null.
          -- Or this is per role quota, then TENANT must be defined.
          TENANT <> '-')
      end),
  constraint DW1_QTAS_VERSION__C_IN
      check (VERSION in ('C', 'O')),
  constraint DW1_QTAS_TIME__C
      check (MTIME >= CTIME),
  constraint DW1_QTAS_TNT__R__TENANTS  -- ix DW1_QTAS_TNT__U
      foreign key (TENANT)
      references DW1_TENANTS(ID) deferrable,
  constraint DW1_QTAS_TNT_ROLE__R__ROLES  -- ix DW1_QTAS_ROLE__U
      foreign key (TENANT, ROLE_ID)
      references DW1_USERS(TENANT, SNO) deferrable
);


------ Unique constraint

-- (I'm afraid this index will eventually be rather skew? For example all
-- global IPs start with tenant '-'. But inserts should be fairly infrequent,
-- and we never delete rows -- we almost only do updates. So I think it
-- won't be noticeable that the index is skew.)
create unique index DW1_QTAS_TNT_IP_ROLE__U on DW1_QUOTAS(
  coalesce(TENANT, '-'), coalesce(IP, '-'), coalesce(ROLE_ID, '-'));

/* I am using '-' instead of Null now, and added a PK, so this not needed:
create unique index DW1_QTAS_IP__U on DW1_QUOTAS(IP)
    where TENANT is null and ROLE_ID is null and VERSION = 'C';
create unique index DW1_QTAS_TNT_IP__U on DW1_QUOTAS(TENANT, IP)
    where ROLE_ID is null and VERSION = 'C';
create unique index DW1_QTAS_ROLE__U on DW1_QUOTAS(TENANT, ROLE_ID)
    where IP is null and VERSION  = 'C';
create unique index DW1_QTAS_TNT__U on DW1_QUOTAS(TENANT)
    where IP is null and ROLE_ID is null and VERSION = 'C';
*/
