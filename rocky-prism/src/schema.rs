use crate::types::PrismError;
use rocky_sdk::{Affinity, Column, Schema, Table};

pub fn prism_schema() -> Result<Schema, PrismError> {
    Schema::builder("prism", 1)
        .table(
            Table::replayed("profiles")
                .column(Column::new("slug", Affinity::Text).required())
                .column(Column::new("label", Affinity::Text).required())
                .column(Column::new("position", Affinity::Integer).required()),
        )
        .table(
            Table::replayed("profile_entries")
                .column(Column::new("profile", Affinity::Text).required())
                .column(Column::new("position", Affinity::Integer).required())
                .column(Column::new("instance_id", Affinity::Text).required())
                .column(Column::new("model", Affinity::Text).required())
                .column(
                    Column::new("reasoning", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(
                    Column::new("enabled", Affinity::Integer)
                        .required()
                        .default(true),
                )
                .column(Column::new("session_share_of_weekly", Affinity::Real)),
        )
        .table(
            Table::replayed("actions")
                .column(Column::new("action", Affinity::Text).required())
                .column(Column::new("profile", Affinity::Text).required()),
        )
        .table(
            Table::replayed("policy")
                .column(Column::new("key", Affinity::Text).required())
                .column(Column::new("value", Affinity::Text).required()),
        )
        .table(
            Table::replayed("hosts")
                .column(Column::new("name", Affinity::Text).required())
                .column(Column::new("seen_at", Affinity::Integer).required())
                .column(
                    Column::new("environment_id", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(Column::new("models_json", Affinity::Text).required()),
        )
        .table(
            Table::replayed("threads")
                .column(Column::new("host", Affinity::Text).required())
                .column(Column::new("thread_id", Affinity::Text).required())
                .column(Column::new("title", Affinity::Text).required().default(""))
                .column(Column::new("profile", Affinity::Text).required())
                .column(Column::new("selection_json", Affinity::Text).required())
                .column(Column::new("model", Affinity::Text).required())
                .column(
                    Column::new("running", Affinity::Integer)
                        .required()
                        .default(false),
                )
                .column(Column::new("updated_at", Affinity::Integer).required())
                // The `hosts` row id (hex) of the report this thread came in.
                .column(Column::new("report", Affinity::Text).required().default("")),
        )
        .table(
            Table::replayed("instructions")
                .column(Column::new("instruction_id", Affinity::Text).required())
                .column(Column::new("host", Affinity::Text).required())
                .column(Column::new("thread_id", Affinity::Text).required())
                .column(Column::new("kind", Affinity::Text).required())
                .column(Column::new("from_selection_json", Affinity::Text).required())
                .column(Column::new("selection_json", Affinity::Text).required())
                .column(Column::new("reason", Affinity::Text).required())
                .column(Column::new("state", Affinity::Text).required())
                .column(Column::new("created_at", Affinity::Integer).required())
                .column(Column::new("done_at", Affinity::Integer)),
        )
        .table(
            // One row per acknowledgement, keyed by the move's `instruction_id`.
            // Written even when the move row has not replicated here yet.
            Table::replayed("move_acks")
                .column(Column::new("instruction_id", Affinity::Text).required())
                .column(Column::new("host", Affinity::Text).required())
                .column(Column::new("state", Affinity::Text).required())
                .column(Column::new("at", Affinity::Integer).required()),
        )
        .table(
            Table::replayed("samples")
                .column(Column::new("account", Affinity::Text).required())
                .column(Column::new("at", Affinity::Integer).required())
                .column(Column::new("used", Affinity::Real).required())
                .column(Column::new("running", Affinity::Integer).required()),
        )
        .table(
            // One row per alert episode (`key` = fingerprint and episode). Any
            // worker that receives the webhook may insert it, so reads keep the
            // row with the smallest Rocky id per key; updates name the key.
            Table::replayed("alerts")
                .column(Column::new("key", Affinity::Text).required())
                .column(Column::new("fingerprint", Affinity::Text).required())
                .column(Column::new("episode", Affinity::Text).required())
                .column(Column::new("payload", Affinity::Text).required())
                .column(Column::new("name", Affinity::Text).required().default(""))
                .column(
                    Column::new("check_name", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(
                    Column::new("severity", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(
                    Column::new("machine", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(
                    Column::new("summary", Affinity::Text)
                        .required()
                        .default(""),
                )
                // open, resolved, dismissed or gone (no longer in Alertmanager).
                .column(Column::new("state", Affinity::Text).required())
                .column(Column::new("received_at", Affinity::Integer).required())
                .column(Column::new("resolved_at", Affinity::Integer))
                .column(
                    Column::new("missing_since", Affinity::Integer)
                        .required()
                        .default(0),
                ),
        )
        .table(
            // The owner's `#42` per episode, given by the leader. A number two
            // keys were given in a leader handover belongs to the earlier row.
            Table::replayed("alert_numbers")
                .column(Column::new("key", Affinity::Text).required())
                .column(Column::new("number", Affinity::Integer).required())
                .column(Column::new("at", Affinity::Integer).required()),
        )
        .table(
            // fix, dismiss and resolve: a device writes the request, the leader
            // claims it, runs it and marks it done.
            Table::replayed("alert_actions")
                .column(Column::new("request_id", Affinity::Text).required())
                .column(Column::new("alert", Affinity::Text).required())
                .column(Column::new("kind", Affinity::Text).required())
                .column(
                    Column::new("instruction", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(Column::new("requested_by", Affinity::Text).required())
                .column(Column::new("requested_at", Affinity::Integer).required())
                .column(Column::new("claimed_by", Affinity::Text))
                .column(Column::new("claimed_at", Affinity::Integer))
                .column(Column::new("done_at", Affinity::Integer))
                .column(
                    Column::new("thread_id", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(Column::new("link", Affinity::Text).required().default(""))
                .column(Column::new("error", Affinity::Text).required().default("")),
        )
        .table(
            // The ntfy wake-ups the leader posted, by notification key.
            Table::replayed("pushes")
                .column(Column::new("key", Affinity::Text).required())
                .column(Column::new("at", Affinity::Integer).required()),
        )
        .table(
            // Paired MCP sessions with a machine's T3 (`prism pair`).
            Table::replayed("t3_sessions")
                .column(Column::new("machine", Affinity::Text).required())
                .column(Column::new("origin", Affinity::Text).required())
                .column(Column::new("token", Affinity::Text).required())
                .column(
                    Column::new("environment_id", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(Column::new("label", Affinity::Text).required().default(""))
                .column(Column::new("access", Affinity::Text).required().default(""))
                .column(Column::new("paired_at", Affinity::Integer).required())
                .column(
                    Column::new("expires_at", Affinity::Integer)
                        .required()
                        .default(0),
                ),
        )
        .table(
            // T3 client API sessions (`PUT /v1/t3/client-session`), used for approvals.
            Table::replayed("t3_client_sessions")
                .column(Column::new("machine", Affinity::Text).required())
                .column(Column::new("origin", Affinity::Text).required())
                .column(Column::new("token", Affinity::Text).required())
                .column(
                    Column::new("environment_id", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(Column::new("expires_at", Affinity::Integer).required()),
        )
        .table(
            // Pending/answered/closed T3 command approvals.
            Table::replayed("approvals")
                .column(Column::new("approval_id", Affinity::Text).required())
                .column(Column::new("machine", Affinity::Text).required())
                .column(Column::new("thread_id", Affinity::Text).required())
                .column(Column::new("request_id", Affinity::Text).required())
                .column(Column::new("payload", Affinity::Text).required())
                .column(Column::new("state", Affinity::Text).required())
                .column(Column::new("created_at", Affinity::Integer).required())
                .column(Column::new("updated_at", Affinity::Integer).required()),
        )
        .table(
            // Owner's signed answer to an approval, claimed and dispatched by the leader.
            Table::replayed("approval_answers")
                .column(Column::new("request_id", Affinity::Text).required())
                .column(Column::new("approval_id", Affinity::Text).required())
                .column(Column::new("message", Affinity::Text).required())
                .column(
                    Column::new("signature", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(Column::new("requested_by", Affinity::Text).required())
                .column(Column::new("requested_at", Affinity::Integer).required())
                .column(Column::new("claimed_by", Affinity::Text))
                .column(Column::new("claimed_at", Affinity::Integer))
                .column(Column::new("done_at", Affinity::Integer))
                .column(Column::new("error", Affinity::Text).required().default("")),
        )
        .table(
            // Pending/answered/closed T3 user questions across all reachable T3s.
            Table::replayed("questions")
                .column(Column::new("question_id", Affinity::Text).required())
                .column(Column::new("thread_id", Affinity::Text).required())
                .column(
                    Column::new("environment_id", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(Column::new("host", Affinity::Text).required())
                .column(Column::new("client", Affinity::Text).required())
                .column(Column::new("via", Affinity::Text).required().default(""))
                .column(Column::new("title", Affinity::Text).required().default(""))
                .column(Column::new("payload", Affinity::Text).required())
                .column(Column::new("state", Affinity::Text).required())
                .column(Column::new("created_at", Affinity::Integer).required())
                .column(Column::new("updated_at", Affinity::Integer).required()),
        )
        .table(
            // Owner's answer to a pending question, claimed and sent by the leader.
            Table::replayed("question_answers")
                .column(Column::new("request_id", Affinity::Text).required())
                .column(Column::new("question_id", Affinity::Text).required())
                .column(Column::new("answers_json", Affinity::Text).required())
                .column(Column::new("requested_by", Affinity::Text).required())
                .column(Column::new("requested_at", Affinity::Integer).required())
                .column(Column::new("claimed_by", Affinity::Text))
                .column(Column::new("claimed_at", Affinity::Integer))
                .column(Column::new("done_at", Affinity::Integer))
                .column(Column::new("error", Affinity::Text).required().default("")),
        )
        .table(
            // Prism's own agents (started from alerts or agent_requests).
            Table::replayed("agents")
                .column(Column::new("thread_id", Affinity::Text).required())
                .column(Column::new("kind", Affinity::Text).required())
                .column(Column::new("title", Affinity::Text).required().default(""))
                .column(
                    Column::new("alert_id", Affinity::Integer)
                        .required()
                        .default(0),
                )
                .column(Column::new("target", Affinity::Text).required())
                .column(Column::new("client", Affinity::Text).required())
                .column(
                    Column::new("environment_id", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(Column::new("host", Affinity::Text).required())
                .column(Column::new("link", Affinity::Text).required().default(""))
                .column(
                    Column::new("request_text", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(Column::new("state", Affinity::Text).required())
                .column(
                    Column::new("run_status", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(
                    Column::new("question_pending", Affinity::Integer)
                        .required()
                        .default(false),
                )
                .column(
                    Column::new("last_message", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(
                    Column::new("requested_by", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(Column::new("created_at", Affinity::Integer).required())
                .column(Column::new("updated_at", Affinity::Integer).required())
                .column(
                    Column::new("next_check", Affinity::Integer)
                        .required()
                        .default(0),
                )
                .column(
                    Column::new("reported_run", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(
                    Column::new("terminal_run", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(
                    Column::new("terminal_seen", Affinity::Integer)
                        .required()
                        .default(0),
                ),
        )
        .table(
            // Device request to start a new agent, claimed and launched by the leader.
            Table::replayed("agent_requests")
                .column(Column::new("request_id", Affinity::Text).required())
                .column(Column::new("task", Affinity::Text).required())
                .column(Column::new("target", Affinity::Text).required().default(""))
                .column(Column::new("request_text", Affinity::Text).required())
                .column(
                    Column::new("model_json", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(
                    Column::new("project_id", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(Column::new("requested_by", Affinity::Text).required())
                .column(Column::new("requested_at", Affinity::Integer).required())
                .column(Column::new("claimed_by", Affinity::Text))
                .column(Column::new("claimed_at", Affinity::Integer))
                .column(Column::new("done_at", Affinity::Integer))
                .column(
                    Column::new("thread_id", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(Column::new("link", Affinity::Text).required().default(""))
                .column(Column::new("error", Affinity::Text).required().default("")),
        )
        .table(
            // Device request to send a follow-up message to an agent, or to stop it.
            // `kind` is `message` (send `text`) or `stop` (interrupt the run).
            Table::replayed("agent_messages")
                .column(Column::new("request_id", Affinity::Text).required())
                .column(Column::new("agent_id", Affinity::Text).required())
                .column(
                    Column::new("kind", Affinity::Text)
                        .required()
                        .default("message"),
                )
                .column(Column::new("text", Affinity::Text).required())
                .column(Column::new("requested_by", Affinity::Text).required())
                .column(Column::new("requested_at", Affinity::Integer).required())
                .column(Column::new("claimed_by", Affinity::Text))
                .column(Column::new("claimed_at", Affinity::Integer))
                .column(Column::new("done_at", Affinity::Integer))
                .column(Column::new("error", Affinity::Text).required().default("")),
        )
        .table(
            // Latest activity items of an agent, refreshed by the leader.
            Table::replayed("agent_activity")
                .column(Column::new("agent_id", Affinity::Text).required())
                .column(Column::new("status", Affinity::Text).required().default(""))
                .column(Column::new("items_json", Affinity::Text).required())
                .column(Column::new("updated_at", Affinity::Integer).required()),
        )
        .table(
            // Machine reachability and host metrics, refreshed by the leader.
            Table::replayed("machines")
                .column(Column::new("name", Affinity::Text).required())
                .column(
                    Column::new("status_json", Affinity::Text)
                        .required()
                        .default("{}"),
                )
                .column(
                    Column::new("metrics_json", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(Column::new("updated_at", Affinity::Integer).required()),
        )
        .table(
            // Project catalog across reachable T3s, refreshed by the leader.
            Table::replayed("projects")
                .column(Column::new("project_id", Affinity::Text).required())
                .column(Column::new("name", Affinity::Text).required())
                .column(
                    Column::new("icon_json", Affinity::Text)
                        .required()
                        .default(""),
                )
                .column(
                    Column::new("machines_json", Affinity::Text)
                        .required()
                        .default("[]"),
                )
                .column(
                    Column::new("repository", Affinity::Integer)
                        .required()
                        .default(false),
                )
                .column(Column::new("updated_at", Affinity::Integer).required()),
        )
        .table(
            // Android/Mac settings and model/reasoning defaults.
            Table::replayed("settings")
                .column(Column::new("key", Affinity::Text).required())
                .column(Column::new("value", Affinity::Text).required())
                .column(Column::new("updated_at", Affinity::Integer).required()),
        )
        .table(
            // Published Android APK release metadata per package.
            Table::replayed("android_releases")
                .column(Column::new("package", Affinity::Text).required())
                .column(Column::new("version_code", Affinity::Integer).required())
                .column(Column::new("version_name", Affinity::Text).required())
                .column(Column::new("sha256", Affinity::Text).required())
                .column(Column::new("size", Affinity::Integer).required())
                .column(Column::new("uploaded_at", Affinity::Integer).required())
                .column(Column::new("uploaded_by", Affinity::Text).required())
                .column(
                    Column::new("stored_on", Affinity::Text)
                        .required()
                        .default(""),
                ),
        )
        .build()
        .map_err(|err| PrismError::Storage(err.to_string()))
}
