SELECT
    i.id,
    i.tracker_id,
    i.project_id,
    i.subject,
    i.due_date,
    i.category_id,
    i.status_id,
    i.assigned_to_id,
    i.priority_id,
    i.fixed_version_id,
    i.author_id,
    i.lock_version,
    i.created_on,
    i.updated_on,
    i.start_date,
    i.done_ratio,
    i.estimated_hours,
    i.parent_id,
    i.root_id,
    i.lft,
    i.rgt,
    i.is_private,
    i.closed_on,
    i.ir_position,

    p.id        AS p_id,
    p.name      AS p_name,
    p.parent_id AS p_parent_id,

    t.id        AS t_id,
    t.name      AS t_name,

    c.id        AS c_id,
    c.name      AS c_name,

    s.id        AS s_id,
    s.name      AS s_name,
    s.is_closed AS s_is_closed,

    au.id                AS au_id,
    au.login             AS au_login,
    au.firstname         AS au_firstname,
    au.lastname          AS au_lastname,
    au.mail_notification AS au_email,

    u.id                AS u_id,
    u.login             AS u_login,
    u.firstname         AS u_firstname,
    u.lastname          AS u_lastname,
    u.mail_notification AS u_email

FROM redmine_production.issues i

LEFT OUTER JOIN redmine_production.projects p
    ON p.id = i.project_id

LEFT OUTER JOIN redmine_production.trackers t
    ON t.id = i.tracker_id

LEFT OUTER JOIN redmine_production.issue_categories c
    ON c.id = i.category_id

LEFT OUTER JOIN redmine_production.issue_statuses s
    ON s.id = i.status_id

LEFT OUTER JOIN redmine_production.users au
    ON au.id = i.author_id

LEFT OUTER JOIN redmine_production.users u
    ON u.id = i.assigned_to_id;