-- Issue #39: authorization-relevant tool effects are immutable version metadata. Existing pre-V26 tools receive a
-- conservative sentinel at CRITICAL risk; operators must publish a new tool version to declare a usable exact effect.

alter table agent_tool_version add column effect_resource varchar(256) not null default 'legacy-unclassified';
alter table agent_tool_version add column effect_action varchar(128) not null default 'invoke';
alter table agent_tool_version add column effect_risk varchar(16) not null default 'CRITICAL';

alter table agent_tool_version alter column effect_resource drop default;
alter table agent_tool_version alter column effect_action drop default;
alter table agent_tool_version alter column effect_risk drop default;

alter table agent_tool_version add constraint agent_tool_effect_risk_ck
    check (effect_risk in ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL'));
