-- ---------------------------------------------------------------------------
-- Schema 5: Das Rig einer Fassung.
--
-- Bis hierher gab es nur humanoide Clips, also musste niemand aufschreiben,
-- welches Rig eine Fassung trägt - es war immer dasselbe. Mit generischen
-- Clips ist es eine Tatsache, die man nicht mehr raten kann, und der Viewer
-- entscheidet daran, OB die Figur überhaupt auftritt: ein generischer Clip
-- läuft auf seinem eigenen Skelett, nicht auf dem Mannequin.
--
-- Der Standardwert ist kein Platzhalter, sondern die Wahrheit über alles, was
-- vor dieser Zeile hochgeladen wurde: humanoid war die einzige Möglichkeit.
-- ---------------------------------------------------------------------------

alter table package_version add column rig varchar(16) not null default 'humanoid';
