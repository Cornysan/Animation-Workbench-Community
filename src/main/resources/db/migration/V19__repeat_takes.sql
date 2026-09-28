-- ---------------------------------------------------------------------------
-- Schema 19: jeder Import aus der Workbench ist ein Download (2026-09-28).
--
-- take_count bleibt die Quittung: eine je Konto, nie am eigenen Clip - daran
-- haengen "popular", die Meilensteine und die Auszeichnungen. Pablo will aber
-- unter dem Clip jeden Import sehen. Was die Workbench holt, ohne dass eine
-- neue Quittung entsteht (dasselbe Konto noch einmal, oder der eigene Clip),
-- zaehlt deshalb hier und geht nur in die Summe "Downloads" ein.
-- ---------------------------------------------------------------------------

alter table animation_package add column repeat_take_count bigint not null default 0;
