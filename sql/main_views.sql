-- redmine-issues
create view redmine_production.v_issues as
select i.*,
       p.id as p_id,p.name as p_name,p.parent_id as p_parent_id,
       t.id as t_id,t.name as t_name,
       c.id as c_id,c.name as c_name,
       s.id as s_id,s.name as s_name,s.is_closed as s_is_closed,
       au.id as au_id,au.login as au_login,au.firstname as au_firstname,au.lastname as au_lastname,au.mail_notification as auemail,
       u.id as u_id,u.login as u_login,u.firstname as u_firstname,u.lastname as u_lastname,u.mail_notification as u_email
from
    redmine_production.issues i,
    redmine_production.projects p,
    redmine_production.trackers t,
    redmine_production.issue_categories c,
    redmine_production.issue_statuses s,
    redmine_production.users au,
    redmine_production.users u
where
    p.id = i.project_id and
    t.id = i.tracker_id and
    c.id = i.category_id and
    au.id = i.author_id and
    s.id = i.status_id
;
