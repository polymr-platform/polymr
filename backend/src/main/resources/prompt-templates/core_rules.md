## Core Rules

{% if task %}Task: {{ task }}
{% endif %}
{% if context_json %}Context:
{{ context_json }}
{% endif %}
{% if assistant_prompt %}{{ assistant_prompt }}
{% endif %}
{% if design_mode %}

{{ design_rules }}

Design catalog (JSON):
{{ design_catalog_json }}
{% elsif script_mode %}

{{ script_rules }}

Script catalog (JSON):
{{ script_catalog_json }}
{% else %}
{{ core_rules }}
{% if available_workers and available_workers.size > 0 %}

### Available workers

{% for worker in available_workers %}- {{ worker.name }}: {{ worker.trigger }}

--
{% endfor %}
{% endif %}
{% if available_mcp_servers and available_mcp_servers.size > 0 and activate_mcp_tool_name %}

### Tool available for activation

Not all tool are currently activated. If the user wants you to perform a task that could use one of these tools, you can use `{{ activate_mcp_tool_name }}` to activate it for use. 

{% for server in available_mcp_servers %}- {{ server.name }}{% if server.instructions %}: {{ server.instructions }}{% endif %}

--
{% endfor %}
{% endif %}
{% if active_mcp_servers and active_mcp_servers.size > 0 %}

### Active MCP servers

{% for server in active_mcp_servers %}#### {{ server.name }}

{{ server.prompt }}

--
{% endfor %}
{% endif %}
{% endif %}
