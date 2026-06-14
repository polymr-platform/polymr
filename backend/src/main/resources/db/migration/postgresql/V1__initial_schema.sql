CREATE TABLE assistant_rules (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    enabled boolean NOT NULL,
    rule_order integer,
    assistant_id uuid NOT NULL,
    rule_id uuid NOT NULL
);
CREATE TABLE assistant_skills (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    assistant_id uuid NOT NULL,
    skill_id uuid NOT NULL
);
CREATE TABLE assistants (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    description character varying(255),
    name character varying(255) NOT NULL,
    persona_id uuid,
    tenant_id uuid NOT NULL,
    workspace_id uuid,
    model_id uuid,
    max_output_tokens integer,
    prompt_text text,
    worker_allow_scopes jsonb,
    worker_deny_scopes jsonb,
    worker_trigger character varying(255),
    worker_enabled boolean DEFAULT false NOT NULL,
    max_turns integer
);
CREATE TABLE channel_mcp_servers (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    available_on_request boolean NOT NULL,
    enabled_by_default boolean NOT NULL,
    channel_id uuid NOT NULL,
    mcp_server_id uuid NOT NULL
);
CREATE TABLE channel_scopes (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    allow_scopes jsonb,
    deny_scopes jsonb,
    channel_id uuid NOT NULL
);
CREATE TABLE channel_tag_selections (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    category_id uuid NOT NULL,
    channel_id uuid NOT NULL,
    value_id uuid
);
CREATE TABLE channels (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    description character varying(255),
    name character varying(255) NOT NULL,
    prompt text,
    assistant_id uuid,
    workspace_id uuid NOT NULL
);
CREATE TABLE mcp_call_logs (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    connection_id uuid NOT NULL,
    input text,
    method character varying(255),
    output text,
    protocol character varying(255),
    request_id integer,
    mcp_server_id uuid,
    session_id uuid,
    tenant_id uuid NOT NULL,
    user_id uuid,
    workspace_id uuid NOT NULL,
    status character varying(255),
    script_call_id uuid,
    script_id uuid,
    mcp_server_name character varying(255),
    mcp_server_protocol character varying(255),
    mcp_server_override_name character varying(255),
    mcp_server_override_tag_name character varying(255)
);
CREATE TABLE mcp_oauth_clients (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    client_id character varying(255),
    client_secret_ciphertext character varying(255),
    client_secret_hint character varying(255),
    client_secret_nonce character varying(255),
    dynamic_registration boolean NOT NULL,
    provider_id uuid NOT NULL
);
CREATE TABLE mcp_oauth_provider_scope_categories (
    provider_id uuid NOT NULL,
    category_id uuid NOT NULL
);
CREATE TABLE mcp_oauth_providers (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    authorization_endpoint character varying(255),
    issuer character varying(255),
    registration_endpoint character varying(255),
    resource_metadata_url character varying(255),
    token_endpoint character varying(255),
    well_known_url character varying(255),
    workspace_id uuid NOT NULL,
    scopes character varying(255),
    global_auth boolean DEFAULT true NOT NULL
);
CREATE TABLE mcp_oauth_session_scope_values (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    session_id uuid NOT NULL,
    tag_value_id uuid NOT NULL
);
CREATE TABLE mcp_oauth_sessions (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    code_verifier character varying(255) NOT NULL,
    redirect_uri character varying(255) NOT NULL,
    state character varying(255) NOT NULL,
    mcp_server_id uuid NOT NULL,
    provider_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    user_id uuid,
    auth_scope_value_id uuid
);
CREATE TABLE mcp_oauth_token_scope_values (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    tag_value_id uuid NOT NULL,
    token_id uuid NOT NULL
);
CREATE TABLE mcp_oauth_tokens (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    access_expires_at timestamp(6) with time zone,
    access_token_ciphertext character varying(255),
    access_token_hint character varying(255),
    access_token_nonce character varying(255),
    refresh_expires_at timestamp(6) with time zone,
    refresh_token_ciphertext character varying(255),
    refresh_token_hint character varying(255),
    refresh_token_nonce character varying(255),
    provider_id uuid NOT NULL,
    source_token_id uuid,
    workspace_id uuid NOT NULL,
    user_id uuid,
    active boolean DEFAULT true NOT NULL,
    auth_scope_value_id uuid,
    refresh_error_message character varying(255),
    refresh_failed_at timestamp(6) with time zone
);
CREATE TABLE mcp_server_applications (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    app_name character varying(255),
    app_uri character varying(255) NOT NULL,
    disabled boolean NOT NULL,
    display_name character varying(255),
    icon_svg text,
    mcp_server_id uuid NOT NULL
);
CREATE TABLE mcp_server_configs (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    config_json jsonb NOT NULL,
    mcp_server_id uuid NOT NULL,
    tag_id uuid
);
CREATE TABLE mcp_server_overrides (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    headers character varying(255),
    http_url character varying(255),
    logical_name character varying(255) NOT NULL,
    ssh_enabled boolean,
    ssh_tunnel jsonb,
    mcp_server_id uuid NOT NULL,
    oauth_provider_id uuid,
    tag_id uuid NOT NULL
);
CREATE TABLE mcp_server_policies (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    policy_json jsonb NOT NULL,
    mcp_server_id uuid NOT NULL,
    tag_id uuid NOT NULL
);
CREATE TABLE mcp_server_tools (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    deleted boolean NOT NULL,
    description text,
    disabled boolean NOT NULL,
    input_schema jsonb,
    preview_supported boolean NOT NULL,
    scopes jsonb,
    tool_name text NOT NULL,
    mcp_server_id uuid NOT NULL,
    output_schema jsonb,
    tool_alias text,
    custom_scopes jsonb,
    intent_template text,
    dynamic_scopes boolean DEFAULT false NOT NULL,
    input_template text,
    output_template text,
    meta jsonb
);
CREATE TABLE mcp_servers (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    command character varying(255),
    cwd character varying(255),
    description character varying(255),
    environment character varying(255),
    framing character varying(255),
    headers character varying(255),
    http_url character varying(255),
    name character varying(255) NOT NULL,
    protocol character varying(255) NOT NULL,
    workspace_id uuid NOT NULL,
    allow_policy boolean NOT NULL,
    config_schema jsonb,
    configuration_json jsonb,
    supports_dynamic_config boolean NOT NULL,
    tools_hash character varying(255),
    oauth_enabled boolean DEFAULT false NOT NULL,
    oauth_provider_id uuid,
    virtual_type character varying(255),
    instructions text,
    custom_instructions boolean DEFAULT false NOT NULL,
    internal boolean DEFAULT false NOT NULL,
    visibility character varying(255) DEFAULT 'VISIBLE'::text NOT NULL,
    ssh_enabled boolean DEFAULT false NOT NULL,
    ssh_tunnel jsonb,
    prompt text,
    tool_name_prefix text,
    CONSTRAINT mcp_servers_protocol_check CHECK (((protocol)::text = ANY ((ARRAY['STDIO'::character varying, 'SSE'::character varying, 'STREAMABLE_HTTP'::character varying, 'VIRTUAL'::character varying])::text[])))
);
CREATE TABLE models (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    enabled boolean NOT NULL,
    name character varying(255) NOT NULL,
    provider character varying(255) NOT NULL,
    tenant_id uuid NOT NULL,
    config_json jsonb NOT NULL,
    type character varying(255) DEFAULT 'CHAT'::text NOT NULL
);
CREATE TABLE notification_logs (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    body character varying(255) NOT NULL,
    destination character varying(255) NOT NULL,
    eligible_count integer NOT NULL,
    sent_count integer NOT NULL,
    target character varying(255) NOT NULL,
    title character varying(255) NOT NULL,
    initiator_user_id uuid,
    session_id uuid,
    workspace_id uuid NOT NULL,
    CONSTRAINT notification_logs_target_check CHECK (((target)::text = ANY ((ARRAY['CURRENT_USER'::character varying, 'CONVERSATION_PARTICIPANTS'::character varying, 'WORKSPACE_MEMBERS'::character varying])::text[])))
);
CREATE TABLE notification_recipients (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    detail character varying(255),
    status character varying(255) NOT NULL,
    notification_log_id uuid NOT NULL,
    user_id uuid NOT NULL
);
CREATE TABLE personas (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    description character varying(255),
    name character varying(255) NOT NULL,
    tenant_id uuid NOT NULL,
    prompt_text text
);
CREATE TABLE prompt_templates (
    id uuid NOT NULL,
    content text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    enabled boolean NOT NULL,
    section character varying(255) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    tenant_id uuid,
    workspace_id uuid,
    CONSTRAINT prompt_templates_section_check CHECK (((section)::text = ANY (ARRAY[('PERSONALITY'::character varying)::text, ('CORE_RULES'::character varying)::text, ('SKILLS'::character varying)::text, ('FORMATTING'::character varying)::text, ('WORKER_AUTONOMY'::character varying)::text])))
);
CREATE TABLE push_subscriptions (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    active boolean NOT NULL,
    auth character varying(255) NOT NULL,
    endpoint text NOT NULL,
    last_seen_at timestamp(6) with time zone,
    p256dh character varying(255) NOT NULL,
    user_agent character varying(255),
    user_id uuid NOT NULL
);
CREATE TABLE push_workspace_preferences (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    enabled boolean NOT NULL,
    user_id uuid NOT NULL,
    workspace_id uuid NOT NULL
);
CREATE TABLE recording_rules (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    recording_id uuid NOT NULL,
    rule_id uuid NOT NULL
);
CREATE TABLE recordings (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    audio_hash character varying(255) NOT NULL,
    audio_mime_type character varying(255),
    audio_size_bytes bigint,
    duration_seconds integer,
    error_message character varying(2048),
    optimized_text text,
    status character varying(255) NOT NULL,
    summary_text text,
    title character varying(255) NOT NULL,
    transcript_text text,
    created_by_user_id uuid,
    tenant_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    started_at timestamp without time zone,
    CONSTRAINT recordings_status_check CHECK (((status)::text = ANY ((ARRAY['UPLOADED'::character varying, 'TRANSCRIBING'::character varying, 'TRANSCRIBED'::character varying, 'OPTIMIZING'::character varying, 'OPTIMIZED'::character varying, 'SUMMARIZING'::character varying, 'SUMMARIZED'::character varying, 'ERROR'::character varying])::text[])))
);
CREATE TABLE rules (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    always_included boolean NOT NULL,
    content text NOT NULL,
    enabled boolean NOT NULL,
    name character varying(255) NOT NULL,
    rule_order integer,
    scope character varying(255) NOT NULL,
    type character varying(255),
    tenant_id uuid,
    user_id uuid,
    workspace_id uuid,
    CONSTRAINT rules_scope_check CHECK (((scope)::text = ANY ((ARRAY['USER'::character varying, 'TENANT'::character varying, 'WORKSPACE'::character varying])::text[])))
);
CREATE TABLE runtime_heartbeats (
    server_id character varying(255) NOT NULL,
    last_seen_at timestamp(6) with time zone NOT NULL,
    started_at timestamp(6) with time zone NOT NULL
);
CREATE TABLE script_call_logs (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    input text,
    output text,
    status character varying(255),
    script_id uuid NOT NULL,
    session_id uuid,
    tenant_id uuid NOT NULL,
    user_id uuid,
    workspace_id uuid NOT NULL
);
CREATE TABLE script_versions (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    deprecated_at timestamp(6) with time zone,
    design_session_id uuid,
    released_at timestamp(6) with time zone,
    source_groovy text,
    created_by_user_id uuid,
    released_by_user_id uuid,
    script_id uuid NOT NULL,
    input_schema jsonb,
    output_schema jsonb,
    version integer DEFAULT 1 NOT NULL
);
CREATE TABLE scripts (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    description character varying(255),
    disabled boolean NOT NULL,
    input_schema jsonb,
    name character varying(255) NOT NULL,
    output_schema jsonb,
    slug character varying(255) NOT NULL,
    active_version_id uuid,
    created_by_user_id uuid,
    workspace_id uuid NOT NULL,
    script_type character varying(255) DEFAULT 'STANDALONE'::character varying NOT NULL,
    workflow_definition_id uuid,
    last_run_at timestamp(6) with time zone,
    next_run_at timestamp(6) with time zone,
    schedule_end_at timestamp(6) with time zone,
    schedule_rrule character varying(255),
    schedule_start_at timestamp(6) with time zone,
    schedule_timezone character varying(255),
    tool_hook_phase character varying(255),
    tool_hook_tool_names jsonb,
    scheduled boolean DEFAULT false NOT NULL,
    tool_hook_enabled boolean DEFAULT false NOT NULL,
    namespace character varying(255),
    CONSTRAINT scripts_tool_hook_phase_check CHECK (((tool_hook_phase)::text = ANY ((ARRAY['BEFORE'::character varying, 'AFTER'::character varying])::text[])))
);
CREATE TABLE secret_store (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    ciphertext text NOT NULL,
    secret_key character varying(255) NOT NULL,
    nonce character varying(255) NOT NULL
);
CREATE TABLE session_canvases (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    compiled_bundle text NOT NULL,
    logical_id character varying(255) NOT NULL,
    source_sfc text NOT NULL,
    title character varying(255) NOT NULL,
    session_id uuid NOT NULL,
    updated_by_user_id uuid
);
CREATE TABLE session_cost_entries (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    amount numeric(18,6),
    breakdown_json text,
    cache_ratio double precision,
    cached_input_tokens bigint,
    currency character varying(255),
    input_tokens bigint,
    model_id character varying(255),
    output_tokens bigint,
    reasoning_tokens bigint,
    session_id uuid NOT NULL
);
CREATE TABLE session_event_resources (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    blob_hash character varying(255) NOT NULL,
    mime_type character varying(255),
    uri character varying(255) NOT NULL,
    session_event_id uuid NOT NULL,
    workspace_id uuid NOT NULL
);
CREATE TABLE session_events (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    event_type character varying(255) NOT NULL,
    payload_json jsonb,
    session_id uuid NOT NULL,
    input_tokens integer,
    output_tokens integer,
    price_currency character varying(255),
    price_snapshot numeric(19,6),
    reasoning_tokens integer,
    tokenizer_model_id character varying(255),
    epoch_id integer DEFAULT 1 NOT NULL,
    cached_input_tokens integer,
    pruned_payload_json jsonb,
    location_lat double precision,
    location_lng double precision,
    user_id uuid,
    CONSTRAINT session_events_event_type_check CHECK (((event_type)::text = ANY (ARRAY[('USER_MESSAGE'::character varying)::text, ('CONTEXT_MESSAGE'::character varying)::text, ('ASSISTANT_MESSAGE'::character varying)::text, ('SYSTEM'::character varying)::text, ('TOOL_CALL'::character varying)::text, ('TOOL_RESULT'::character varying)::text, ('DECISION_REQUEST'::character varying)::text, ('DECISION_RESULT'::character varying)::text, ('SESSION_TAG_CHANGE'::character varying)::text, ('STAGE_START'::character varying)::text, ('STAGE_END'::character varying)::text, ('AUDIT'::character varying)::text])))
);
CREATE TABLE session_participant_connections (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    active boolean NOT NULL,
    connection_id character varying(255) NOT NULL,
    last_active_at timestamp(6) with time zone,
    session_id uuid NOT NULL,
    user_id uuid NOT NULL,
    last_seen_at timestamp(6) with time zone,
    server_id character varying(255)
);
CREATE TABLE session_participants (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    role character varying(255) NOT NULL,
    session_id uuid NOT NULL,
    user_id uuid NOT NULL,
    active boolean NOT NULL,
    last_active_at timestamp(6) with time zone,
    CONSTRAINT session_participants_role_check CHECK (((role)::text = ANY ((ARRAY['OWNER'::character varying, 'PARTICIPANT'::character varying])::text[])))
);
CREATE TABLE session_tag_selections (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    category_id uuid NOT NULL,
    session_id uuid NOT NULL,
    value_id uuid
);
CREATE TABLE session_tag_states (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    tag_type character varying(255) NOT NULL,
    active_tag_id uuid,
    session_id uuid NOT NULL
);
CREATE TABLE sessions (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    status character varying(255) NOT NULL,
    title character varying(255),
    created_by_user_id uuid,
    assistant_id uuid,
    tenant_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    visibility character varying(255) NOT NULL,
    locked boolean NOT NULL,
    title_locked boolean NOT NULL,
    cumulative_cached_input_tokens bigint,
    cumulative_input_tokens bigint,
    last_cached_input_tokens bigint,
    last_input_tokens bigint,
    last_output_tokens bigint,
    last_reasoning_tokens bigint,
    total_cost numeric(18,6),
    total_cost_currency character varying(255),
    cumulative_output_tokens bigint,
    cumulative_reasoning_tokens bigint,
    channel_id uuid,
    parent_session_id uuid,
    current_activity jsonb,
    cumulative_pruned_tokens bigint,
    pruning_threshold_offset_ratio double precision,
    CONSTRAINT sessions_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'PAUSED'::character varying, 'COMPLETED'::character varying, 'ERROR'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT sessions_visibility_check CHECK (((visibility)::text = ANY ((ARRAY['PRIVATE'::character varying, 'WORKSPACE'::character varying, 'HIDDEN'::character varying, 'FLEXIBLE'::character varying])::text[])))
);
CREATE TABLE sfc_page_dependencies (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    depends_on_id uuid NOT NULL,
    page_id uuid NOT NULL
);
CREATE TABLE sfc_page_installations (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    page_id uuid NOT NULL,
    user_id uuid NOT NULL
);
CREATE TABLE sfc_page_versions (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    released_at timestamp(6) with time zone,
    compile_errors text,
    compiled_bundle text,
    design_session_id uuid,
    source_sfc text,
    released_by_user_id uuid,
    created_by_user_id uuid,
    page_id uuid NOT NULL,
    deprecated_at timestamp with time zone,
    version integer DEFAULT 1 NOT NULL
);
CREATE TABLE sfc_pages (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    description character varying(255),
    import_allowlist jsonb,
    input_params jsonb,
    menu_visible boolean NOT NULL,
    name character varying(255) NOT NULL,
    query_params jsonb,
    route_suffix character varying(255),
    slug character varying(255) NOT NULL,
    page_type character varying(255) NOT NULL,
    active_version_id uuid,
    created_by_user_id uuid,
    workspace_id uuid NOT NULL,
    icon_svg text,
    disabled boolean DEFAULT false NOT NULL,
    namespace character varying(255),
    label character varying(255),
    usage_guide text,
    CONSTRAINT sfc_pages_page_type_check CHECK (((page_type)::text = ANY ((ARRAY['PAGE'::character varying, 'COMPONENT'::character varying])::text[])))
);
CREATE TABLE skills (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    description character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    trigger character varying(255) NOT NULL,
    tenant_id uuid NOT NULL,
    prompt_text text,
    workspace_id uuid,
    always_included boolean DEFAULT false NOT NULL
);
CREATE TABLE tag_categories (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    deleted_at timestamp(6) with time zone,
    name character varying(255) NOT NULL,
    priority integer NOT NULL,
    slug character varying(255) NOT NULL,
    tenant_id uuid NOT NULL,
    workspace_id uuid
);
CREATE TABLE tag_values (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    deleted_at timestamp(6) with time zone,
    name character varying(255) NOT NULL,
    priority integer NOT NULL,
    slug character varying(255) NOT NULL,
    category_id uuid NOT NULL
);
CREATE TABLE tags (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    name character varying(255) NOT NULL,
    type character varying(255) NOT NULL,
    workspace_id uuid NOT NULL
);
CREATE TABLE tenant_automation_tasks (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    enabled boolean NOT NULL,
    prompt_text text,
    task_type character varying(255) NOT NULL,
    model_id uuid NOT NULL,
    tenant_id uuid NOT NULL
);
CREATE TABLE tenant_memberships (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    role character varying(255) NOT NULL,
    tenant_id uuid NOT NULL,
    user_id uuid NOT NULL,
    CONSTRAINT tenant_memberships_role_check CHECK (((role)::text = ANY ((ARRAY['OWNER'::character varying, 'ADMIN'::character varying, 'MEMBER'::character varying])::text[])))
);
CREATE TABLE tenants (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    name character varying(255) NOT NULL,
    owner_user_id uuid NOT NULL,
    max_output_tokens integer
);
CREATE TABLE tokens (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    device_id character varying(255) NOT NULL,
    expires_at timestamp(6) with time zone NOT NULL,
    revoked_at timestamp(6) with time zone,
    secret_hash character varying(512) NOT NULL,
    type character varying(255) NOT NULL,
    used_at timestamp(6) with time zone,
    user_id uuid NOT NULL,
    CONSTRAINT tokens_type_check CHECK (((type)::text = ANY ((ARRAY['ACCESS'::character varying, 'REFRESH'::character varying])::text[])))
);
CREATE TABLE users (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    display_name character varying(255),
    email character varying(255) NOT NULL,
    family_name character varying(255),
    given_name character varying(255),
    password_hash character varying(255) NOT NULL,
    password_salt character varying(255) NOT NULL,
    nickname character varying(255),
    avatar_url character varying(255),
    avatar_bytes bytea,
    avatar_content_type character varying(255),
    avatar_updated_at timestamp(6) with time zone,
    notifications_snoozed_until timestamp(6) with time zone,
    execution_mode smallint,
    CONSTRAINT users_execution_mode_check CHECK (((execution_mode >= 0) AND (execution_mode <= 1)))
);
CREATE TABLE workflow_definition_versions (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    definition_json jsonb NOT NULL,
    deprecated_at timestamp(6) with time zone,
    design_session_id uuid,
    released_at timestamp(6) with time zone,
    created_by_user_id uuid,
    released_by_user_id uuid,
    workflow_definition_id uuid NOT NULL
);
CREATE TABLE workflow_definitions (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    description character varying(255),
    name character varying(255) NOT NULL,
    tenant_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    start_trigger character varying(255) DEFAULT 'USER_PROMPT'::character varying NOT NULL,
    deleted_at timestamp(6) with time zone,
    disabled boolean DEFAULT false NOT NULL
);
CREATE TABLE workflow_run_checkpoints (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    node_id character varying(255),
    state_json jsonb,
    step_index integer NOT NULL,
    workflow_run_id uuid NOT NULL
);
CREATE TABLE workflow_runs (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    checkpoint_json jsonb,
    current_node character varying(255),
    status character varying(255) NOT NULL,
    session_id uuid NOT NULL,
    workflow_definition_id uuid NOT NULL,
    runtime_server_id character varying(255),
    workflow_definition_version_id uuid,
    CONSTRAINT workflow_runs_status_check CHECK (((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'LEASED'::character varying, 'RUNNING'::character varying, 'PAUSED'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[])))
);
CREATE TABLE workspace_asset_blobs (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    byte_size bigint NOT NULL,
    bytes bytea NOT NULL,
    hash character varying(255) NOT NULL,
    mime_type character varying(255),
    workspace_id uuid NOT NULL
);
CREATE TABLE workspace_asset_bundle_entries (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    blob_hash character varying(255) NOT NULL,
    mime_type character varying(255),
    resource_path character varying(255) NOT NULL,
    source_uri character varying(255),
    bundle_id uuid NOT NULL
);
CREATE TABLE workspace_asset_bundles (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    csp_json text,
    root_blob_hash character varying(255) NOT NULL,
    root_mime_type character varying(255),
    root_path character varying(255) NOT NULL,
    root_source_uri character varying(255),
    mcp_server_id uuid,
    workspace_id uuid NOT NULL,
    type character varying(255)
);
CREATE TABLE workspace_automation_assistants (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    task_type character varying(255) NOT NULL,
    assistant_id uuid NOT NULL,
    workspace_id uuid NOT NULL
);
CREATE TABLE workspace_permissions (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    allow_scopes jsonb,
    deny_scopes jsonb,
    workspace_id uuid NOT NULL
);
CREATE TABLE workspace_scopes (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    allow_scopes jsonb,
    deny_scopes jsonb,
    workspace_id uuid NOT NULL
);
CREATE TABLE workspace_tag_selections (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    category_id uuid NOT NULL,
    value_id uuid,
    workspace_id uuid NOT NULL
);
CREATE TABLE workspace_tag_states (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    tag_type character varying(255) NOT NULL,
    active_tag_id uuid,
    workspace_id uuid NOT NULL
);
CREATE TABLE workspaces (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    name character varying(255) NOT NULL,
    tenant_id uuid NOT NULL,
    description character varying(255),
    external_frontend_imports jsonb
);
ALTER TABLE ONLY assistant_rules
    ADD CONSTRAINT assistant_rules_pkey PRIMARY KEY (id);
ALTER TABLE ONLY assistant_skills
    ADD CONSTRAINT assistant_skills_pkey PRIMARY KEY (id);
ALTER TABLE ONLY assistants
    ADD CONSTRAINT assistants_pkey PRIMARY KEY (id);
ALTER TABLE ONLY channel_mcp_servers
    ADD CONSTRAINT channel_mcp_servers_pkey PRIMARY KEY (id);
ALTER TABLE ONLY channel_scopes
    ADD CONSTRAINT channel_scopes_pkey PRIMARY KEY (id);
ALTER TABLE ONLY channel_tag_selections
    ADD CONSTRAINT channel_tag_selections_pkey PRIMARY KEY (id);
ALTER TABLE ONLY channels
    ADD CONSTRAINT channels_pkey PRIMARY KEY (id);
ALTER TABLE ONLY mcp_call_logs
    ADD CONSTRAINT mcp_call_logs_pkey PRIMARY KEY (id);
ALTER TABLE ONLY mcp_oauth_provider_scope_categories
    ADD CONSTRAINT mcp_oauth_provider_scope_categories_pkey PRIMARY KEY (provider_id, category_id);
ALTER TABLE ONLY mcp_oauth_session_scope_values
    ADD CONSTRAINT mcp_oauth_session_scope_values_pkey PRIMARY KEY (id);
ALTER TABLE ONLY mcp_oauth_token_scope_values
    ADD CONSTRAINT mcp_oauth_token_scope_values_pkey PRIMARY KEY (id);
ALTER TABLE ONLY mcp_oauth_clients
    ADD CONSTRAINT mcp_oidc_clients_pkey PRIMARY KEY (id);
ALTER TABLE ONLY mcp_oauth_providers
    ADD CONSTRAINT mcp_oidc_providers_pkey PRIMARY KEY (id);
ALTER TABLE ONLY mcp_oauth_sessions
    ADD CONSTRAINT mcp_oidc_sessions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY mcp_oauth_tokens
    ADD CONSTRAINT mcp_oidc_tokens_pkey PRIMARY KEY (id);
ALTER TABLE ONLY mcp_server_applications
    ADD CONSTRAINT mcp_server_applications_pkey PRIMARY KEY (id);
ALTER TABLE ONLY mcp_server_configs
    ADD CONSTRAINT mcp_server_configs_pkey PRIMARY KEY (id);
ALTER TABLE ONLY mcp_server_overrides
    ADD CONSTRAINT mcp_server_overrides_pkey PRIMARY KEY (id);
ALTER TABLE ONLY mcp_server_policies
    ADD CONSTRAINT mcp_server_policies_pkey PRIMARY KEY (id);
ALTER TABLE ONLY mcp_server_tools
    ADD CONSTRAINT mcp_server_tools_pkey PRIMARY KEY (id);
ALTER TABLE ONLY mcp_servers
    ADD CONSTRAINT mcp_servers_pkey PRIMARY KEY (id);
ALTER TABLE ONLY models
    ADD CONSTRAINT models_pkey PRIMARY KEY (id);
ALTER TABLE ONLY notification_logs
    ADD CONSTRAINT notification_logs_pkey PRIMARY KEY (id);
ALTER TABLE ONLY notification_recipients
    ADD CONSTRAINT notification_recipients_pkey PRIMARY KEY (id);
ALTER TABLE ONLY personas
    ADD CONSTRAINT personas_pkey PRIMARY KEY (id);
ALTER TABLE ONLY prompt_templates
    ADD CONSTRAINT prompt_templates_pkey PRIMARY KEY (id);
ALTER TABLE ONLY push_subscriptions
    ADD CONSTRAINT push_subscriptions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY push_workspace_preferences
    ADD CONSTRAINT push_workspace_preferences_pkey PRIMARY KEY (id);
ALTER TABLE ONLY recording_rules
    ADD CONSTRAINT recording_rules_pkey PRIMARY KEY (id);
ALTER TABLE ONLY recordings
    ADD CONSTRAINT recordings_pkey PRIMARY KEY (id);
ALTER TABLE ONLY rules
    ADD CONSTRAINT rules_pkey PRIMARY KEY (id);
ALTER TABLE ONLY runtime_heartbeats
    ADD CONSTRAINT runtime_heartbeats_pkey PRIMARY KEY (server_id);
ALTER TABLE ONLY script_call_logs
    ADD CONSTRAINT script_call_logs_pkey PRIMARY KEY (id);
ALTER TABLE ONLY script_versions
    ADD CONSTRAINT script_versions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY scripts
    ADD CONSTRAINT scripts_pkey PRIMARY KEY (id);
ALTER TABLE ONLY scripts
    ADD CONSTRAINT scripts_workspace_id_slug_key UNIQUE (workspace_id, slug);
ALTER TABLE ONLY secret_store
    ADD CONSTRAINT secret_store_pkey PRIMARY KEY (id);
ALTER TABLE ONLY session_canvases
    ADD CONSTRAINT session_canvases_pkey PRIMARY KEY (id);
ALTER TABLE ONLY session_cost_entries
    ADD CONSTRAINT session_cost_entries_pkey PRIMARY KEY (id);
ALTER TABLE ONLY session_event_resources
    ADD CONSTRAINT session_event_resources_pkey PRIMARY KEY (id);
ALTER TABLE ONLY session_events
    ADD CONSTRAINT session_events_pkey PRIMARY KEY (id);
ALTER TABLE ONLY session_participant_connections
    ADD CONSTRAINT session_participant_connections_pkey PRIMARY KEY (id);
ALTER TABLE ONLY session_participants
    ADD CONSTRAINT session_participants_pkey PRIMARY KEY (id);
ALTER TABLE ONLY session_tag_selections
    ADD CONSTRAINT session_tag_selections_pkey PRIMARY KEY (id);
ALTER TABLE ONLY session_tag_states
    ADD CONSTRAINT session_tag_states_pkey PRIMARY KEY (id);
ALTER TABLE ONLY sessions
    ADD CONSTRAINT sessions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY sfc_page_dependencies
    ADD CONSTRAINT sfc_page_dependencies_pkey PRIMARY KEY (id);
ALTER TABLE ONLY sfc_page_installations
    ADD CONSTRAINT sfc_page_installations_pkey PRIMARY KEY (id);
ALTER TABLE ONLY sfc_page_versions
    ADD CONSTRAINT sfc_page_versions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY sfc_pages
    ADD CONSTRAINT sfc_pages_pkey PRIMARY KEY (id);
ALTER TABLE ONLY sfc_pages
    ADD CONSTRAINT sfc_pages_workspace_id_slug_key UNIQUE (workspace_id, slug);
ALTER TABLE ONLY skills
    ADD CONSTRAINT skills_pkey PRIMARY KEY (id);
ALTER TABLE ONLY tag_categories
    ADD CONSTRAINT tag_categories_pkey PRIMARY KEY (id);
ALTER TABLE ONLY tag_values
    ADD CONSTRAINT tag_values_pkey PRIMARY KEY (id);
ALTER TABLE ONLY tags
    ADD CONSTRAINT tags_pkey PRIMARY KEY (id);
ALTER TABLE ONLY tenant_automation_tasks
    ADD CONSTRAINT tenant_automation_tasks_pkey PRIMARY KEY (id);
ALTER TABLE ONLY tenant_memberships
    ADD CONSTRAINT tenant_memberships_pkey PRIMARY KEY (id);
ALTER TABLE ONLY tenants
    ADD CONSTRAINT tenants_pkey PRIMARY KEY (id);
ALTER TABLE ONLY tokens
    ADD CONSTRAINT tokens_pkey PRIMARY KEY (id);
ALTER TABLE ONLY channel_scopes
    ADD CONSTRAINT uk1jo54f0gkijj39offajhotgbj UNIQUE (channel_id);
ALTER TABLE ONLY workspace_asset_blobs
    ADD CONSTRAINT uk2etdk5k8mj1njfbl6isfqtxew UNIQUE (workspace_id, hash);
ALTER TABLE ONLY workspace_tag_states
    ADD CONSTRAINT uk33ws8qg09u2g65jwx6s2a48xp UNIQUE (workspace_id, tag_type);
ALTER TABLE ONLY recording_rules
    ADD CONSTRAINT uk35k95rq43j214h4fprwaf9wxf UNIQUE (recording_id, rule_id);
ALTER TABLE ONLY assistant_skills
    ADD CONSTRAINT uk5wm4pj533lpfl80w22n3sndhc UNIQUE (assistant_id, skill_id);
ALTER TABLE ONLY assistant_rules
    ADD CONSTRAINT uk5xu365dgpyx8dpciu6x8933sj UNIQUE (assistant_id, rule_id);
ALTER TABLE ONLY users
    ADD CONSTRAINT uk6dotkott2kjsp8vw4d0m25fb7 UNIQUE (email);
ALTER TABLE ONLY session_canvases
    ADD CONSTRAINT uk75lbtmyivvsu3oj0ko2jj10g1 UNIQUE (session_id, logical_id);
ALTER TABLE ONLY mcp_oauth_sessions
    ADD CONSTRAINT uk7eat4r20psml3eqcnuohoot8s UNIQUE (state);
ALTER TABLE ONLY workspace_permissions
    ADD CONSTRAINT uk81p8lpgn0as4m2q9n5fbupn9n UNIQUE (workspace_id);
ALTER TABLE ONLY workspace_asset_bundle_entries
    ADD CONSTRAINT uk89tx998qfd8giwyoiqu0mgogt UNIQUE (bundle_id, resource_path);
ALTER TABLE ONLY mcp_oauth_token_scope_values
    ADD CONSTRAINT uk9gpmnmtw81862j2pno7u0i2eg UNIQUE (token_id, tag_value_id);
ALTER TABLE ONLY workspace_asset_bundles
    ADD CONSTRAINT uk9s38scufsj07r4rcqbcc1yr9b UNIQUE (workspace_id, root_blob_hash);
ALTER TABLE ONLY scripts
    ADD CONSTRAINT uka2541cactin2kpfn772axib4i UNIQUE (workspace_id, slug);
ALTER TABLE ONLY session_tag_states
    ADD CONSTRAINT uka6fjvdbchrqwrh8j26p8v3ehm UNIQUE (session_id, tag_type);
ALTER TABLE ONLY mcp_oauth_clients
    ADD CONSTRAINT ukanqkn2snaigml0r26e35jeawx UNIQUE (provider_id);
ALTER TABLE ONLY workspace_scopes
    ADD CONSTRAINT ukasp3ks97d9tjmykcgwh1uaew7 UNIQUE (workspace_id);
ALTER TABLE ONLY channel_tag_selections
    ADD CONSTRAINT ukb40gldgd50bh430pdd5cn83n1 UNIQUE (channel_id, category_id);
ALTER TABLE ONLY skills
    ADD CONSTRAINT ukckqrt1ty43090oxauyl8paa27 UNIQUE (tenant_id, workspace_id, name);
ALTER TABLE ONLY session_tag_selections
    ADD CONSTRAINT ukcsigvgpuvbx1jflahonl19n3p UNIQUE (session_id, category_id);
ALTER TABLE ONLY mcp_oauth_sessions
    ADD CONSTRAINT ukdg4ooyqrmxvjyvju1ippms2bx UNIQUE (state);
ALTER TABLE ONLY session_participants
    ADD CONSTRAINT ukfdqyet3ih7rnm4ytera4h0039 UNIQUE (session_id, user_id);
ALTER TABLE ONLY mcp_server_policies
    ADD CONSTRAINT ukhg4qvcqxnsavm3yt7ikck0dta UNIQUE (mcp_server_id, tag_id);
ALTER TABLE ONLY mcp_server_overrides
    ADD CONSTRAINT ukhngcg6repbs30cy4ug0pq7awf UNIQUE (mcp_server_id, tag_id);
ALTER TABLE ONLY mcp_oauth_clients
    ADD CONSTRAINT ukiecukox17g9bbh4gxg7uid2u9 UNIQUE (provider_id);
ALTER TABLE ONLY secret_store
    ADD CONSTRAINT ukivksq5dg1xqvvyl723dg6y9kf UNIQUE (secret_key);
ALTER TABLE ONLY channel_mcp_servers
    ADD CONSTRAINT ukjqdw1tgav8plctyctffrn84py UNIQUE (channel_id, mcp_server_id);
ALTER TABLE ONLY workflow_runs
    ADD CONSTRAINT ukl3dbg7i2ayaio4jfshemm9r88 UNIQUE (session_id);
ALTER TABLE ONLY push_workspace_preferences
    ADD CONSTRAINT ukl554y3rbmg5mt4qeopk2m3hjy UNIQUE (workspace_id, user_id);
ALTER TABLE ONLY mcp_server_configs
    ADD CONSTRAINT uknbvtqemdjw85r0tu80je3452y UNIQUE (mcp_server_id, tag_id);
ALTER TABLE ONLY mcp_server_applications
    ADD CONSTRAINT ukne7s2kq41rwmravfmqqb4s1b9 UNIQUE (mcp_server_id, app_uri);
ALTER TABLE ONLY sfc_pages
    ADD CONSTRAINT uko3yj8nvl5lbts987s0whhjt4c UNIQUE (workspace_id, slug);
ALTER TABLE ONLY mcp_server_tools
    ADD CONSTRAINT ukofnustqkjf8qkesmwkevkdsw8 UNIQUE (mcp_server_id, tool_name);
ALTER TABLE ONLY mcp_oauth_session_scope_values
    ADD CONSTRAINT ukoo48rw2an35nxnxmadn6unrji UNIQUE (session_id, tag_value_id);
ALTER TABLE ONLY workspace_automation_assistants
    ADD CONSTRAINT ukoqbf28dyie0x20k3c25qfpsum UNIQUE (workspace_id, assistant_id, task_type);
ALTER TABLE ONLY workspace_tag_selections
    ADD CONSTRAINT ukovpimh5xueurcq7hbk6v43xnt UNIQUE (workspace_id, category_id);
ALTER TABLE ONLY session_participant_connections
    ADD CONSTRAINT ukq5e4iurvxwmbvwoc7ouesuxto UNIQUE (session_id, user_id, connection_id);
ALTER TABLE ONLY tenant_memberships
    ADD CONSTRAINT ukqnh36a5lgqdhbg3uhro6x3mtt UNIQUE (tenant_id, user_id);
ALTER TABLE ONLY tenant_automation_tasks
    ADD CONSTRAINT ukqwjs2tsdbiy6l9rn7l1473rb1 UNIQUE (tenant_id, task_type);
ALTER TABLE ONLY push_subscriptions
    ADD CONSTRAINT uks18x30ads2sflt7wtr064jv82 UNIQUE (user_id, endpoint);
ALTER TABLE ONLY users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);
ALTER TABLE ONLY workflow_definition_versions
    ADD CONSTRAINT workflow_definition_versions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY workflow_definitions
    ADD CONSTRAINT workflow_definitions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY workflow_run_checkpoints
    ADD CONSTRAINT workflow_run_checkpoints_pkey PRIMARY KEY (id);
ALTER TABLE ONLY workflow_runs
    ADD CONSTRAINT workflow_runs_pkey PRIMARY KEY (id);
ALTER TABLE ONLY workspace_asset_blobs
    ADD CONSTRAINT workspace_asset_blobs_pkey PRIMARY KEY (id);
ALTER TABLE ONLY workspace_asset_bundle_entries
    ADD CONSTRAINT workspace_asset_bundle_entries_pkey PRIMARY KEY (id);
ALTER TABLE ONLY workspace_asset_bundles
    ADD CONSTRAINT workspace_asset_bundles_pkey PRIMARY KEY (id);
ALTER TABLE ONLY workspace_automation_assistants
    ADD CONSTRAINT workspace_automation_assistants_pkey PRIMARY KEY (id);
ALTER TABLE ONLY workspace_permissions
    ADD CONSTRAINT workspace_permissions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY workspace_scopes
    ADD CONSTRAINT workspace_scopes_pkey PRIMARY KEY (id);
ALTER TABLE ONLY workspace_tag_selections
    ADD CONSTRAINT workspace_tag_selections_pkey PRIMARY KEY (id);
ALTER TABLE ONLY workspace_tag_states
    ADD CONSTRAINT workspace_tag_states_pkey PRIMARY KEY (id);
ALTER TABLE ONLY workspaces
    ADD CONSTRAINT workspaces_pkey PRIMARY KEY (id);
CREATE INDEX idx_mcp_call_logs_override_name ON mcp_call_logs USING btree (mcp_server_override_name);
CREATE INDEX idx_mcp_call_logs_override_tag ON mcp_call_logs USING btree (mcp_server_override_tag_name);
CREATE INDEX idx_mcp_server_overrides_oauth_provider ON mcp_server_overrides USING btree (oauth_provider_id);
CREATE INDEX idx_mcp_server_overrides_server ON mcp_server_overrides USING btree (mcp_server_id);
CREATE INDEX idx_mcp_server_overrides_tag ON mcp_server_overrides USING btree (tag_id);
CREATE INDEX idx_session_events_session_type_created ON session_events USING btree (session_id, event_type, created_at);
CREATE INDEX idx_session_events_user ON session_events USING btree (user_id);
CREATE INDEX sessions_parent_session_idx ON sessions USING btree (parent_session_id);
CREATE UNIQUE INDEX ux_script_versions_script_id_version ON script_versions USING btree (script_id, version);
CREATE UNIQUE INDEX ux_sfc_page_versions_page_id_version ON sfc_page_versions USING btree (page_id, version);
ALTER TABLE ONLY workflow_runs
    ADD CONSTRAINT fk1317ho2fqv82ph29jjuh8oxye FOREIGN KEY (workflow_definition_id) REFERENCES workflow_definitions(id);
ALTER TABLE ONLY assistants
    ADD CONSTRAINT fk16frwxnwsy80m2gx5o66b38vs FOREIGN KEY (model_id) REFERENCES models(id);
ALTER TABLE ONLY channel_scopes
    ADD CONSTRAINT fk1akg5arrhwcslrvg2hn1opj9g FOREIGN KEY (channel_id) REFERENCES channels(id);
ALTER TABLE ONLY mcp_oauth_token_scope_values
    ADD CONSTRAINT fk1d7c27el8bo7etwo81f757ma5 FOREIGN KEY (tag_value_id) REFERENCES tag_values(id);
ALTER TABLE ONLY tags
    ADD CONSTRAINT fk1o0rb9term6ckug308ppd7331 FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY session_participant_connections
    ADD CONSTRAINT fk1qwbcg8jgh1ksx9hdv04pyvg1 FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY mcp_oauth_provider_scope_categories
    ADD CONSTRAINT fk1r1ncwayc25wogf8oeserx2mv FOREIGN KEY (provider_id) REFERENCES mcp_oauth_providers(id);
ALTER TABLE ONLY assistant_skills
    ADD CONSTRAINT fk1rubuedsx8d6wckpaowmxhac8 FOREIGN KEY (assistant_id) REFERENCES assistants(id);
ALTER TABLE ONLY push_subscriptions
    ADD CONSTRAINT fk1v577hpc7v9mdrm2uyk6kqgnl FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY workflow_definitions
    ADD CONSTRAINT fk28fj1yr8xb9yxjexthhha1osk FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY tokens
    ADD CONSTRAINT fk2dylsfo39lgjyqml2tbe0b0ss FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY mcp_oauth_tokens
    ADD CONSTRAINT fk35xogybj63esvc0bqak9a9xr FOREIGN KEY (source_token_id) REFERENCES mcp_oauth_tokens(id);
ALTER TABLE ONLY session_tag_selections
    ADD CONSTRAINT fk378wh31odeo8h442wlfcbw0rc FOREIGN KEY (value_id) REFERENCES tag_values(id);
ALTER TABLE ONLY recordings
    ADD CONSTRAINT fk39gt22i69f5oqfurmhqv26lw9 FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY assistants
    ADD CONSTRAINT fk3drq3hpb6b6k3jcjx739g8494 FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY mcp_call_logs
    ADD CONSTRAINT fk3g3l2e8aqq7j8vhsqrn0orqwj FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY mcp_oauth_providers
    ADD CONSTRAINT fk3uei9e5d0njk1tts45hoevj78 FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY workspace_scopes
    ADD CONSTRAINT fk46yj6d2vxrajwbgpoojv8ca6o FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY push_workspace_preferences
    ADD CONSTRAINT fk4ah3saq7pj6pmvf2lww241oed FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY workspace_tag_selections
    ADD CONSTRAINT fk4x0aeehjrkn24kedp3uv1vhw9 FOREIGN KEY (value_id) REFERENCES tag_values(id);
ALTER TABLE ONLY sfc_page_versions
    ADD CONSTRAINT fk54if05iwrs1eea37vm42r978p FOREIGN KEY (page_id) REFERENCES sfc_pages(id);
ALTER TABLE ONLY recording_rules
    ADD CONSTRAINT fk5qd6vag9rkbqe92lmf5iwm41a FOREIGN KEY (recording_id) REFERENCES recordings(id);
ALTER TABLE ONLY script_call_logs
    ADD CONSTRAINT fk5trq6d30x6ht1tdhnklexk6qv FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY channel_tag_selections
    ADD CONSTRAINT fk5ulywg6hbth7hhl5nog395ihj FOREIGN KEY (channel_id) REFERENCES channels(id);
ALTER TABLE ONLY rules
    ADD CONSTRAINT fk62sapqdc5v87rrv4x156sk6ln FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY mcp_server_tools
    ADD CONSTRAINT fk6aly9s7o47jy6tjqe0jfjhmgt FOREIGN KEY (mcp_server_id) REFERENCES mcp_servers(id);
ALTER TABLE ONLY mcp_call_logs
    ADD CONSTRAINT fk6c1ekcvro8iwhsqo4w8s6wnir FOREIGN KEY (session_id) REFERENCES sessions(id);
ALTER TABLE ONLY sessions
    ADD CONSTRAINT fk6d56tx27bcia4y2loj712cc0y FOREIGN KEY (created_by_user_id) REFERENCES users(id);
ALTER TABLE ONLY mcp_oauth_session_scope_values
    ADD CONSTRAINT fk6mdrx2b2u65t2j5et73i97i7p FOREIGN KEY (tag_value_id) REFERENCES tag_values(id);
ALTER TABLE ONLY recordings
    ADD CONSTRAINT fk6n5x96o9la9w81a6yg18s1tn5 FOREIGN KEY (created_by_user_id) REFERENCES users(id);
ALTER TABLE ONLY channels
    ADD CONSTRAINT fk6sm2dry33aemqyix6ydqmjrj6 FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY workspaces
    ADD CONSTRAINT fk6v30hhfbh8p2l8j0faybqvf7e FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY recording_rules
    ADD CONSTRAINT fk764dlknv6d8haacimpyk3hb82 FOREIGN KEY (rule_id) REFERENCES rules(id);
ALTER TABLE ONLY models
    ADD CONSTRAINT fk7de6drkcrtre8ap28e2hmmyg0 FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY tag_categories
    ADD CONSTRAINT fk7kt78rmdtilplpirexw8e8mpi FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY session_tag_states
    ADD CONSTRAINT fk8kw0ujus49luu3skctfgleef0 FOREIGN KEY (session_id) REFERENCES sessions(id);
ALTER TABLE ONLY recordings
    ADD CONSTRAINT fk8p8wn251y9tulsqm978uw90q2 FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY session_participants
    ADD CONSTRAINT fk8xfwj36mmvol1rv36lrjoy4yy FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY mcp_oauth_session_scope_values
    ADD CONSTRAINT fk94bm5wtc4a3grrhumdb0101yg FOREIGN KEY (session_id) REFERENCES mcp_oauth_sessions(id);
ALTER TABLE ONLY mcp_server_overrides
    ADD CONSTRAINT fk9a4ih3erclmhayj5vj4arsbke FOREIGN KEY (mcp_server_id) REFERENCES mcp_servers(id);
ALTER TABLE ONLY sessions
    ADD CONSTRAINT fk9et8gn8npv94cqsbsb1r1j78u FOREIGN KEY (channel_id) REFERENCES channels(id);
ALTER TABLE ONLY scripts
    ADD CONSTRAINT fk9j476uvwpo45deqhx9xx761ju FOREIGN KEY (active_version_id) REFERENCES script_versions(id);
ALTER TABLE ONLY mcp_oauth_tokens
    ADD CONSTRAINT fk9qv309774y3tys9u40242inl3 FOREIGN KEY (auth_scope_value_id) REFERENCES tag_values(id);
ALTER TABLE ONLY mcp_oauth_clients
    ADD CONSTRAINT fk9uhqanh6qdpbta6ylooh0vsfa FOREIGN KEY (provider_id) REFERENCES mcp_oauth_providers(id);
ALTER TABLE ONLY workspace_automation_assistants
    ADD CONSTRAINT fk9wr69t4syxqdxq0wagco104h2 FOREIGN KEY (assistant_id) REFERENCES assistants(id);
ALTER TABLE ONLY session_participant_connections
    ADD CONSTRAINT fk9xi8o5v2acsy2iis1yoaknufg FOREIGN KEY (session_id) REFERENCES sessions(id);
ALTER TABLE ONLY workspace_automation_assistants
    ADD CONSTRAINT fkb4cw6mf69rsd3lwgitwr9jrl7 FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY session_cost_entries
    ADD CONSTRAINT fkb8ga2sljp5al7c8gvkhr1pl7p FOREIGN KEY (session_id) REFERENCES sessions(id);
ALTER TABLE ONLY assistant_rules
    ADD CONSTRAINT fkbun34ujxj7rdivx0ea1rv8c7k FOREIGN KEY (rule_id) REFERENCES rules(id);
ALTER TABLE ONLY mcp_oauth_provider_scope_categories
    ADD CONSTRAINT fkbxc21hlktmksyr2luyqf8nwli FOREIGN KEY (category_id) REFERENCES tag_categories(id);
ALTER TABLE ONLY workflow_runs
    ADD CONSTRAINT fkc247quhgf4jukv7plunlonjk3 FOREIGN KEY (session_id) REFERENCES sessions(id);
ALTER TABLE ONLY script_call_logs
    ADD CONSTRAINT fkcbcrgycxwtkkam8wmqsnqlag1 FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY mcp_server_policies
    ADD CONSTRAINT fkccb3huouws1b7cr9k2qwb85lj FOREIGN KEY (tag_id) REFERENCES tag_values(id);
ALTER TABLE ONLY notification_recipients
    ADD CONSTRAINT fkce9mdpy7u99n8tn3s2iflds4t FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY workflow_definition_versions
    ADD CONSTRAINT fkcmfgmeqsg69nqwrov1pytag1j FOREIGN KEY (released_by_user_id) REFERENCES users(id);
ALTER TABLE ONLY session_participants
    ADD CONSTRAINT fkcnrgb2l4q6ou1o9pxj7ie0x35 FOREIGN KEY (session_id) REFERENCES sessions(id);
ALTER TABLE ONLY mcp_server_configs
    ADD CONSTRAINT fkcyl43kqt4ftn7wxo5qurq0fe7 FOREIGN KEY (mcp_server_id) REFERENCES mcp_servers(id);
ALTER TABLE ONLY mcp_server_policies
    ADD CONSTRAINT fkd3635ggodx7dsxws6ie8m2na2 FOREIGN KEY (mcp_server_id) REFERENCES mcp_servers(id);
ALTER TABLE ONLY channel_tag_selections
    ADD CONSTRAINT fkdcqteyif7yb3s8ulgn7846b84 FOREIGN KEY (value_id) REFERENCES tag_values(id);
ALTER TABLE ONLY mcp_server_configs
    ADD CONSTRAINT fkdh0yvfuw7hthp7x9fmawfgonc FOREIGN KEY (tag_id) REFERENCES tag_values(id);
ALTER TABLE ONLY channel_tag_selections
    ADD CONSTRAINT fkdigvjyqrr2w8adwq6hpraxdyr FOREIGN KEY (category_id) REFERENCES tag_categories(id);
ALTER TABLE ONLY sessions
    ADD CONSTRAINT fkdja1x9sa90wc6x0pjkyuqahgb FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY session_event_resources
    ADD CONSTRAINT fkdl11l072xpqboxkak0cd2s90 FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY sfc_pages
    ADD CONSTRAINT fkdm409681ubmev30tx0lt030w4 FOREIGN KEY (created_by_user_id) REFERENCES users(id);
ALTER TABLE ONLY session_tag_states
    ADD CONSTRAINT fkdvjnue62u8rl33x9jwrxukkqw FOREIGN KEY (active_tag_id) REFERENCES tags(id);
ALTER TABLE ONLY session_event_resources
    ADD CONSTRAINT fkdvybj96s9jvxapmyj663vgc36 FOREIGN KEY (session_event_id) REFERENCES session_events(id);
ALTER TABLE ONLY tenant_memberships
    ADD CONSTRAINT fkdwd7dgwbe15d4xk27cab013do FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY sfc_page_dependencies
    ADD CONSTRAINT fkdx9hinxsbikhdcq5yflyppb8d FOREIGN KEY (depends_on_id) REFERENCES sfc_pages(id);
ALTER TABLE ONLY mcp_server_applications
    ADD CONSTRAINT fke62eovih0natnk6cwrhf9lhv8 FOREIGN KEY (mcp_server_id) REFERENCES mcp_servers(id);
ALTER TABLE ONLY workspace_asset_bundles
    ADD CONSTRAINT fkej7fbvp176m3vilsd2mi0bs1f FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY scripts
    ADD CONSTRAINT fkejuxjrmxaluhc1um51et1keyl FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY workspace_asset_blobs
    ADD CONSTRAINT fkelwllmbme888xs34vtgcskuv5 FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY tenants
    ADD CONSTRAINT fkf6e5wu1kdbc25j30oahba4us2 FOREIGN KEY (owner_user_id) REFERENCES users(id);
ALTER TABLE ONLY workspace_tag_states
    ADD CONSTRAINT fkf7tbof3elhymilvwvh8twph3u FOREIGN KEY (active_tag_id) REFERENCES tags(id);
ALTER TABLE ONLY sfc_pages
    ADD CONSTRAINT fkff6ejln4iqor2p4sm32oio05j FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY notification_logs
    ADD CONSTRAINT fkfmeog8cbh0mpk2kkajnkqx0u8 FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY session_events
    ADD CONSTRAINT fkfxhd5y6p6m58pbqteo9rloicg FOREIGN KEY (session_id) REFERENCES sessions(id);
ALTER TABLE ONLY session_canvases
    ADD CONSTRAINT fkfyjxf3r6op3beee1u9f91kain FOREIGN KEY (updated_by_user_id) REFERENCES users(id);
ALTER TABLE ONLY channel_mcp_servers
    ADD CONSTRAINT fkg5atka861u2g8up4smt54gyko FOREIGN KEY (mcp_server_id) REFERENCES mcp_servers(id);
ALTER TABLE ONLY mcp_oauth_sessions
    ADD CONSTRAINT fkgdr9t3je9ogm80ktakqh5mlt2 FOREIGN KEY (auth_scope_value_id) REFERENCES tag_values(id);
ALTER TABLE ONLY workspace_tag_selections
    ADD CONSTRAINT fkgh893frxi7q2m87besneu2f16 FOREIGN KEY (category_id) REFERENCES tag_categories(id);
ALTER TABLE ONLY tag_categories
    ADD CONSTRAINT fkgl9dyx18ht38l8t7ilws0wt3t FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY mcp_oauth_tokens
    ADD CONSTRAINT fkgsk6plaqakap3nt9hgn00sli FOREIGN KEY (provider_id) REFERENCES mcp_oauth_providers(id);
ALTER TABLE ONLY prompt_templates
    ADD CONSTRAINT fkgx9hl7wjlmrfuq153uio2w2ea FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY mcp_call_logs
    ADD CONSTRAINT fkh7gsrh372jgrbeklxajp7cah1 FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY scripts
    ADD CONSTRAINT fkhbi3ape17752mef9paiutwfut FOREIGN KEY (created_by_user_id) REFERENCES users(id);
ALTER TABLE ONLY sfc_page_versions
    ADD CONSTRAINT fkhhh3g3jgjxspkpuq052wa7rhl FOREIGN KEY (released_by_user_id) REFERENCES users(id);
ALTER TABLE ONLY session_tag_selections
    ADD CONSTRAINT fkhli1jwtonh569eyog84g8k72j FOREIGN KEY (session_id) REFERENCES sessions(id);
ALTER TABLE ONLY script_versions
    ADD CONSTRAINT fkhnpjt635uya1s29i0et9jj41t FOREIGN KEY (released_by_user_id) REFERENCES users(id);
ALTER TABLE ONLY workspace_tag_selections
    ADD CONSTRAINT fkhv4qgwfrjy0w2br6hxarvv6s9 FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY tag_values
    ADD CONSTRAINT fkiejm8c3xnofcbwatdl3f9d0bv FOREIGN KEY (category_id) REFERENCES tag_categories(id);
ALTER TABLE ONLY mcp_server_configs
    ADD CONSTRAINT fkif5jk7ov6gt4u380eib8gjfg5 FOREIGN KEY (tag_id) REFERENCES tags(id);
ALTER TABLE ONLY sessions
    ADD CONSTRAINT fkikw2bmv39hc95fenufyk62k2b FOREIGN KEY (assistant_id) REFERENCES assistants(id);
ALTER TABLE ONLY workspace_tag_states
    ADD CONSTRAINT fkipvx7k0eorrtayhe2feggs10b FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY prompt_templates
    ADD CONSTRAINT fkitld2ce2hv8h742flij48u1fh FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY workflow_definition_versions
    ADD CONSTRAINT fkj58sdgkrv05sv0pxgdd76eygw FOREIGN KEY (created_by_user_id) REFERENCES users(id);
ALTER TABLE ONLY script_versions
    ADD CONSTRAINT fkjbgxc57mwe4w5yekwuabiglq8 FOREIGN KEY (script_id) REFERENCES scripts(id);
ALTER TABLE ONLY session_tag_selections
    ADD CONSTRAINT fkjm7scgpjqa5a2g9mdjsdy7yug FOREIGN KEY (category_id) REFERENCES tag_categories(id);
ALTER TABLE ONLY rules
    ADD CONSTRAINT fkkgxhme9v3fsh2esqif9j28lox FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY mcp_server_configs
    ADD CONSTRAINT fkkjxnw5kl2tj5t8jc9gj67q19e FOREIGN KEY (tag_id) REFERENCES tag_values(id);
ALTER TABLE ONLY mcp_server_overrides
    ADD CONSTRAINT fkktax372l0y375ugquxwcetyph FOREIGN KEY (oauth_provider_id) REFERENCES mcp_oauth_providers(id);
ALTER TABLE ONLY workspace_permissions
    ADD CONSTRAINT fkku8vpd65xt5g4qq6gaud2d542 FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY sfc_page_versions
    ADD CONSTRAINT fkl2jqjn0wt5t910yv83v6itmtb FOREIGN KEY (created_by_user_id) REFERENCES users(id);
ALTER TABLE ONLY session_canvases
    ADD CONSTRAINT fkldvyr1e278882e34s2qhisdra FOREIGN KEY (session_id) REFERENCES sessions(id);
ALTER TABLE ONLY sessions
    ADD CONSTRAINT fkllaoglv8n5vgd8qplvtdhcsl0 FOREIGN KEY (parent_session_id) REFERENCES sessions(id);
ALTER TABLE ONLY tenant_memberships
    ADD CONSTRAINT fklluxk8atn1uo5w5nn7irql32c FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY mcp_servers
    ADD CONSTRAINT fklqwm3y84conlvx0n4b6p1hqyv FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY script_versions
    ADD CONSTRAINT fklw84ce1ywco1xior6d4ot8mp8 FOREIGN KEY (created_by_user_id) REFERENCES users(id);
ALTER TABLE ONLY notification_recipients
    ADD CONSTRAINT fkma0sgliouxmh0www9h91bi7l FOREIGN KEY (notification_log_id) REFERENCES notification_logs(id);
ALTER TABLE ONLY sfc_pages
    ADD CONSTRAINT fkmt45k4kvpcefr60wpfyvqho6q FOREIGN KEY (active_version_id) REFERENCES sfc_page_versions(id);
ALTER TABLE ONLY workspace_asset_bundles
    ADD CONSTRAINT fkmxw9p0rogkwrs2x04cinoux9 FOREIGN KEY (mcp_server_id) REFERENCES mcp_servers(id);
ALTER TABLE ONLY script_call_logs
    ADD CONSTRAINT fkn1qlay6fbjvxutk24cha7qiyb FOREIGN KEY (session_id) REFERENCES sessions(id);
ALTER TABLE ONLY mcp_server_overrides
    ADD CONSTRAINT fkn37bh1ev2dkblevx8doxgsp5u FOREIGN KEY (tag_id) REFERENCES tag_values(id);
ALTER TABLE ONLY sfc_page_installations
    ADD CONSTRAINT fkn5en3w5gwdhagl1r74yxdh9cd FOREIGN KEY (page_id) REFERENCES sfc_pages(id);
ALTER TABLE ONLY mcp_oauth_sessions
    ADD CONSTRAINT fknf6v05xg3tth1kxrunjn65xyu FOREIGN KEY (provider_id) REFERENCES mcp_oauth_providers(id);
ALTER TABLE ONLY assistants
    ADD CONSTRAINT fkniwr1f0n037i8334kurs2uhki FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY mcp_call_logs
    ADD CONSTRAINT fknxj92qgucm9qt8ushcg4hvihi FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY workflow_runs
    ADD CONSTRAINT fko2fw2j9667grftmbcl3gbs4w3 FOREIGN KEY (workflow_definition_version_id) REFERENCES workflow_definition_versions(id);
ALTER TABLE ONLY sfc_page_dependencies
    ADD CONSTRAINT fko57qvpsnq3eajjrmq06h2tj3j FOREIGN KEY (page_id) REFERENCES sfc_pages(id);
ALTER TABLE ONLY sessions
    ADD CONSTRAINT fko8aghndxnouuv4i5ne7ayx6yr FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY sfc_page_installations
    ADD CONSTRAINT fkocy17woe7lldtuxe803eac9k8 FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY rules
    ADD CONSTRAINT fkpbm9sa9my7g9sl35w38h2hglj FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY session_events
    ADD CONSTRAINT fkps4e0xkn5a8t93p45db3qetrx FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY mcp_oauth_sessions
    ADD CONSTRAINT fkpwy7i9oxce0n5br7jowju4qc6 FOREIGN KEY (mcp_server_id) REFERENCES mcp_servers(id);
ALTER TABLE ONLY tenant_automation_tasks
    ADD CONSTRAINT fkpxpgarnmj2xgvvxuk7wca4iax FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY notification_logs
    ADD CONSTRAINT fkq6is9bw0c6i6u3l1fbwksuvts FOREIGN KEY (initiator_user_id) REFERENCES users(id);
ALTER TABLE ONLY tenant_automation_tasks
    ADD CONSTRAINT fkqjdfs4b0e43gv4mgyiv3fo095 FOREIGN KEY (model_id) REFERENCES models(id);
ALTER TABLE ONLY script_call_logs
    ADD CONSTRAINT fkqoeite11vanw4bdq1yl0rlc49 FOREIGN KEY (script_id) REFERENCES scripts(id);
ALTER TABLE ONLY skills
    ADD CONSTRAINT fkqun6orxjtl5071dy9v578e5p3 FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY push_workspace_preferences
    ADD CONSTRAINT fkqupfqrvractyqv9w5riwrk0x6 FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY skills
    ADD CONSTRAINT fkr51n1q20d0xga1w5offa86tyd FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY assistant_skills
    ADD CONSTRAINT fkra5b9av5a7b3faxrnb7395crv FOREIGN KEY (skill_id) REFERENCES skills(id);
ALTER TABLE ONLY channels
    ADD CONSTRAINT fkrnrqxa1s7max71f65ngw7m6qx FOREIGN KEY (assistant_id) REFERENCES assistants(id);
ALTER TABLE ONLY workflow_definition_versions
    ADD CONSTRAINT fkrxis4hrik0guh7d3mypg7562n FOREIGN KEY (workflow_definition_id) REFERENCES workflow_definitions(id);
ALTER TABLE ONLY workflow_definitions
    ADD CONSTRAINT fks4e1h12j6m982hmefeq7p0504 FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY assistant_rules
    ADD CONSTRAINT fks63213ptla99my6qpmcabys41 FOREIGN KEY (assistant_id) REFERENCES assistants(id);
ALTER TABLE ONLY mcp_call_logs
    ADD CONSTRAINT fks920u6pe4164dv0pikhmy4u7a FOREIGN KEY (mcp_server_id) REFERENCES mcp_servers(id);
ALTER TABLE ONLY mcp_call_logs
    ADD CONSTRAINT fksa27xlj7v96bcaxqfs5cuds2r FOREIGN KEY (script_id) REFERENCES scripts(id);
ALTER TABLE ONLY assistants
    ADD CONSTRAINT fksclp53x824llf4rycqb3id6uo FOREIGN KEY (persona_id) REFERENCES personas(id);
ALTER TABLE ONLY mcp_oauth_sessions
    ADD CONSTRAINT fkscq54eot79gwu1rssrh9j429u FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY workflow_run_checkpoints
    ADD CONSTRAINT fksfwbsjra0q6skqrnn67rv6535 FOREIGN KEY (workflow_run_id) REFERENCES workflow_runs(id);
ALTER TABLE ONLY mcp_oauth_token_scope_values
    ADD CONSTRAINT fkskjhlbgol8jlqsldka3xvylo4 FOREIGN KEY (token_id) REFERENCES mcp_oauth_tokens(id);
ALTER TABLE ONLY channel_mcp_servers
    ADD CONSTRAINT fkspljpqfcyk8tbqmcipq01ihu FOREIGN KEY (channel_id) REFERENCES channels(id);
ALTER TABLE ONLY personas
    ADD CONSTRAINT fksrh92pwl7emrfbnvx63gf4mpb FOREIGN KEY (tenant_id) REFERENCES tenants(id);
ALTER TABLE ONLY script_call_logs
    ADD CONSTRAINT fkt6p8lvg9slfbgnrsw0hvt1rom FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY workspace_asset_bundle_entries
    ADD CONSTRAINT fktoylrp6m5ygr9niwj27lv1pq6 FOREIGN KEY (bundle_id) REFERENCES workspace_asset_bundles(id);
ALTER TABLE ONLY mcp_oauth_tokens
    ADD CONSTRAINT fktpyd31whkpghrf281s4j0l2dp FOREIGN KEY (workspace_id) REFERENCES workspaces(id);
ALTER TABLE ONLY mcp_oauth_sessions
    ADD CONSTRAINT mcp_oauth_sessions_auth_scope_value_id_fkey FOREIGN KEY (auth_scope_value_id) REFERENCES tag_values(id);
ALTER TABLE ONLY mcp_oauth_tokens
    ADD CONSTRAINT mcp_oauth_tokens_auth_scope_value_id_fkey FOREIGN KEY (auth_scope_value_id) REFERENCES tag_values(id);
ALTER TABLE ONLY mcp_oauth_sessions
    ADD CONSTRAINT mcp_oidc_sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY mcp_oauth_tokens
    ADD CONSTRAINT mcp_oidc_tokens_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);
ALTER TABLE ONLY mcp_server_policies
    ADD CONSTRAINT mcp_server_policies_tag_id_fkey FOREIGN KEY (tag_id) REFERENCES tag_values(id);
ALTER TABLE ONLY mcp_servers
    ADD CONSTRAINT mcp_servers_oauth_provider_id_fkey FOREIGN KEY (oauth_provider_id) REFERENCES mcp_oauth_providers(id);
ALTER TABLE ONLY notification_logs
    ADD CONSTRAINT notification_logs_session_id_fkey FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE SET NULL;
ALTER TABLE ONLY scripts
    ADD CONSTRAINT scripts_workflow_definition_id_fkey FOREIGN KEY (workflow_definition_id) REFERENCES workflow_definitions(id);
