-- Die Muenzen sind seit dem 2026-09-21 aus dem Code (Pablo: "es soll sowas wie
-- coins nicht geben"). Ihre Tabellen blieben stehen, weil V4 nicht mehr
-- angefasst werden darf - und mit ihnen Zeilen, die keine Datenschutzerklaerung
-- mehr nennt. Zum oeffentlichen Start (2026-09-24) gehen sie ganz.
--
-- BLEIBT: package_unlock samt cost_paid. Die Quittung ist kein Muenz-Ding - sie
-- traegt take_count, "unlockedByMe" und die Auszeichnung "In use", und
-- PackageUnlock bildet cost_paid ab (not null ohne Vorgabe, schreibt immer 0).

drop table quest_progress;
drop table coin_entry;
alter table account drop column coin_balance;
