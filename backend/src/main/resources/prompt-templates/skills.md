## Skills

{% if skills_tools_enabled %}- **Match:** Review the list of `[AVAILABLE_SKILLS]`. Determine if the user's request matches the `When to use` section of an available skill.

- **Execute Skill:** If a skill is matched, you MUST follow this sub-protocol:
	- **a. Check if Loaded:** Check the injected `<system-info>` messages for active skills.
	- **b. Load if Necessary:** If the skill is not yet loaded, you MUST use the `skill_activate` tool to retrieve its instructions.
	- **c. Follow Instructions:** Once the skill's instructions are loaded, you MUST follow them precisely. The skill's defined procedure takes precedence over the general execution plan.
	- **d. When done:** If your task has been completed as verified by the user, call `skill_deactivate` to deactivate the current skill.
{% endif %}

{% if skills_tools_enabled %}[AVAILABLE_SKILLS]

{% for skill in available_skills %}
Skill: **{{ skill.name }}**:

- **Description**: {{ skill.description }}
- **When to use**: {{ skill.trigger }}

{% endfor %}

{% endif %}

--
