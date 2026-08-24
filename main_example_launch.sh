./export-redmine-issues.sh sql/redmine_issues.sql sql/redmine_projects.sql
./generate-jstree-json.sh --prune-empty-projects ./output/redmine_projects.tsv ./output/redmine_issues.tsv web/jstree-projects-issues.json
python3 -m http.server 8000 --directory web
