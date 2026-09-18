-- ---------------------------------------------------------------------------
-- Community-Portal, Schema 1.
-- Datenmodell nach Konzept §4. Geschrieben für PostgreSQL; die Tests laufen
-- gegen H2 im PostgreSQL-Modus, deshalb nur Typen, die beide kennen.
--
-- moderation_action und audit_log sind append-only: der Code hat für beide
-- keine Update- oder Löschpfade. Einzige Ausnahme ist die Pseudonymisierung
-- alter IP-Adressen (Plan O10), die nur ip_address überschreibt.
-- ---------------------------------------------------------------------------

create table account (
    id              uuid primary key,
    discord_id      varchar(64)  not null unique,
    display_name    varchar(80)  not null,
    role            varchar(16)  not null,
    status          varchar(16)  not null,
    strikes         integer      not null default 0,
    false_reports   integer      not null default 0,
    created_at      timestamp with time zone not null,
    last_login_at   timestamp with time zone
);

create table api_token (
    id              uuid primary key,
    account_id      uuid         not null references account (id),
    token_hash      varchar(64)  not null unique,
    label           varchar(80)  not null,
    created_at      timestamp with time zone not null,
    expires_at      timestamp with time zone not null,
    last_used_at    timestamp with time zone,
    revoked_at      timestamp with time zone
);

create table editor_link (
    id                  uuid primary key,
    user_code           varchar(16)  not null unique,
    poll_secret_hash    varchar(64)  not null unique,
    account_id          uuid references account (id),
    created_at          timestamp with time zone not null,
    expires_at          timestamp with time zone not null,
    approved_at         timestamp with time zone,
    consumed_at         timestamp with time zone
);

create table animation_package (
    id                  uuid primary key,
    slug                varchar(16)   not null unique,
    owner_id            uuid          not null references account (id),
    title               varchar(80)   not null,
    description         varchar(2000) not null,
    tags                varchar(400)  not null,
    license             varchar(32)   not null,
    status              varchar(24)   not null,
    current_version_id  uuid,
    download_count      bigint        not null default 0,
    created_at          timestamp with time zone not null,
    updated_at          timestamp with time zone not null
);

create index idx_package_status on animation_package (status);
create index idx_package_owner on animation_package (owner_id);

create table package_version (
    id                  uuid primary key,
    package_id          uuid         not null references animation_package (id),
    version_number      integer      not null,
    content_hash        varchar(64)  not null,
    blob_key            varchar(64)  not null,
    preview_blob_key    varchar(64),
    size_bytes          bigint       not null,
    frame_rate          real         not null,
    duration_seconds    real         not null,
    curve_count         integer      not null,
    origin_class        varchar(16)  not null,
    status              varchar(24)  not null,
    created_at          timestamp with time zone not null,
    constraint uq_version_number unique (package_id, version_number)
);

create index idx_version_hash on package_version (content_hash);

create table upload_declaration (
    id                  uuid primary key,
    account_id          uuid         not null references account (id),
    version_id          uuid         not null references package_version (id),
    declaration_text    varchar(500) not null,
    declaration_version integer      not null,
    license             varchar(32)  not null,
    origin_class        varchar(16)  not null,
    ip_address          varchar(128),
    ip_pseudonymized    boolean      not null default false,
    created_at          timestamp with time zone not null
);

create table report (
    id                  uuid primary key,
    package_id          uuid          not null references animation_package (id),
    reporter_id         uuid          not null references account (id),
    category            varchar(24)   not null,
    message             varchar(2000) not null,
    status              varchar(16)   not null,
    created_at          timestamp with time zone not null,
    resolved_at         timestamp with time zone,
    resolved_by         uuid references account (id),
    resolution_note     varchar(2000)
);

create index idx_report_status on report (status);

create table takedown_request (
    id                  uuid primary key,
    contact_name        varchar(120)  not null,
    contact_email       varchar(254)  not null,
    rights_holder       varchar(200)  not null,
    claimed_work        varchar(2000) not null,
    package_slugs       varchar(1000) not null,
    good_faith          boolean       not null,
    accurate            boolean       not null,
    ip_address          varchar(128),
    ip_pseudonymized    boolean       not null default false,
    status              varchar(16)   not null,
    created_at          timestamp with time zone not null,
    resolved_at         timestamp with time zone,
    resolved_by         uuid references account (id),
    resolution_note     varchar(2000)
);

create index idx_takedown_status on takedown_request (status);

create table moderation_action (
    id              uuid primary key,
    actor_id        uuid references account (id),
    action          varchar(32)   not null,
    target_type     varchar(16)   not null,
    target_id       uuid          not null,
    reason          varchar(2000),
    created_at      timestamp with time zone not null
);

create table audit_log (
    id                  uuid primary key,
    actor_id            uuid,
    action              varchar(48)  not null,
    target_type         varchar(24),
    target_id           varchar(64),
    detail              varchar(2000),
    ip_address          varchar(128),
    ip_pseudonymized    boolean      not null default false,
    created_at          timestamp with time zone not null
);

create index idx_audit_created on audit_log (created_at);

create table notification (
    id              uuid primary key,
    account_id      uuid          not null references account (id),
    message         varchar(1000) not null,
    created_at      timestamp with time zone not null,
    read_at         timestamp with time zone
);

create index idx_notification_account on notification (account_id);

create table system_setting (
    setting_key     varchar(64)  primary key,
    setting_value   varchar(256) not null,
    updated_at      timestamp with time zone not null
);
