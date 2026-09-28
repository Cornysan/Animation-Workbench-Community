-- ---------------------------------------------------------------------------
-- Schema 20: Jede Figur hat ihre eigenen Looks (2026-09-29).
--
-- Bis hierher passte jeder Look auf beide Figuren. Jetzt gehoert jeder genau
-- einer, mit einem Gegenstueck bei der anderen (looks/FigureLooks.kt): Gold
-- und Rose Gold, Galaxy und Nebula, Classic und Crimson als Standard. Sunset
-- ist zur Frau gewandert, Coral gibt es nicht mehr.
--
-- Was schon geteilt ist, bekommt hier das Gegenstueck seiner Figur - so, wie
-- `FigureLooks.lookFor` es fuer jede neue Anfrage auch tut. Das
-- Vorschaubild fuer Discord & Co. zeigt dann den alten Look; es geht mit weg
-- und wird beim naechsten Besuch des Besitzers neu gerendert
-- (web/PreviewCards.kt).
-- ---------------------------------------------------------------------------

update package_version set card_blob_key = null
where package_id in (
    select id from animation_package
    where (figure = 'female' and look in
              ('classic', 'graphite', 'mint', 'ocean', 'marble', 'neon', 'gold', 'chrome', 'galaxy', 'hologram', 'coral'))
       or (figure <> 'female' and look in ('sunset', 'coral'))
);

update animation_package set look = case look
    when 'classic'  then 'crimson'
    when 'graphite' then 'noir'
    when 'mint'     then 'blush'
    when 'ocean'    then 'sunset'
    when 'marble'   then 'rosequartz'
    when 'neon'     then 'magenta'
    when 'gold'     then 'rosegold'
    when 'chrome'   then 'pearl'
    when 'galaxy'   then 'nebula'
    when 'hologram' then 'aurora'
    when 'coral'    then 'crimson'
    else look
end
where figure = 'female';

update animation_package set look = case look
    when 'sunset' then 'ocean'
    when 'coral'  then 'classic'
    else look
end
where figure <> 'female';
