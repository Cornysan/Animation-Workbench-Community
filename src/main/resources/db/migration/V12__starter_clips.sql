-- Starter-Clips (2026-09-24): ein paar Clips aus oeffentlichen CC0-Sammlungen
-- (zuerst Quaternius), damit der Katalog zum Start nicht leer ist.
--
-- Sie gehoeren KEINEM Nutzer, sondern einem eigenen Konto ohne jede Anmeldung
-- - niemand kann sich als "Starter Clips" anmelden, und Admins verwalten die
-- Clips (CatalogService.canManage). Das Konto entsteht HIER und mit fester
-- Kennung, nicht beim ersten Einspielen: so ist der Handle `starter-clips`
-- belegt, bevor ein echter Nutzer ihn sich geben koennte, und niemand kann
-- sich den Namen nehmen und damit als Quelle der Starter-Clips auftreten.
-- Die Kennung steht auch in StarterClips.ACCOUNT_ID.

alter table animation_package add column source_credit varchar(200);
alter table animation_package add column source_url varchar(500);

insert into account (id, display_name, role, status, strikes, false_reports, created_at, handle, bio, follower_count)
values ('00000000-0000-0000-0000-00000000057a', 'Starter Clips', 'USER', 'ACTIVE', 0, 0, current_timestamp,
        'starter-clips',
        'Clips from public CC0 collections, added by the operator of this portal so there is something to start with. None of them was made by a community member - each clip names where it comes from.',
        0);
