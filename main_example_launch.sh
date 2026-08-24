./export-redmine-issues.sh sql/redmine_issues.sql sql/redmine_projects.sql
./generate-jstree-json.sh --prune-empty-projects ./output/redmine_projects.tsv ./output/redmine_issues.tsv output/jstree-projects-issues.json
