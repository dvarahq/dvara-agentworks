\set ON_ERROR_STOP on
\if :{?control_login}
\else
\error 'set control_login, control_password, run_login, and run_password with psql -v'
\endif

select format('create role %I login password %L nosuperuser nocreatedb nocreaterole noreplication nobypassrls '
              'in role agentworks_control_plane', :'control_login', :'control_password')
 where not exists (select 1 from pg_roles where rolname = :'control_login') \gexec
select format('alter role %I login inherit password %L nosuperuser nocreatedb nocreaterole noreplication nobypassrls',
              :'control_login', :'control_password') \gexec
select format('grant agentworks_control_plane to %I', :'control_login') \gexec

select format('create role %I login password %L nosuperuser nocreatedb nocreaterole noreplication nobypassrls '
              'in role agentworks_run_plane', :'run_login', :'run_password')
 where not exists (select 1 from pg_roles where rolname = :'run_login') \gexec
select format('alter role %I login inherit password %L nosuperuser nocreatedb nocreaterole noreplication nobypassrls',
              :'run_login', :'run_password') \gexec
select format('grant agentworks_run_plane to %I', :'run_login') \gexec

select rolname, rolcanlogin, rolsuper, rolcreatedb, rolcreaterole, rolreplication, rolbypassrls
  from pg_roles where rolname in (:'control_login', :'run_login') order by rolname;
