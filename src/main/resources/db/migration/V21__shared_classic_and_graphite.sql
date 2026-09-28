-- ---------------------------------------------------------------------------
-- Schema 21: Classic und Graphite tragen beide Figuren (2026-09-29).
--
-- V20 hatte der Frau eigene Fassungen gegeben - Crimson statt Classic, Noir
-- statt Graphite. Noch am selben Tag zurueck: beide Figuren tragen dieselben
-- zwei, und die Frau hat daneben acht eigene (looks/FigureLooks.kt).
-- Was V20 umgeschrieben hat, kommt hier zurueck; das Vorschaubild zeigt den
-- alten Look und geht mit weg (web/PreviewCards.kt).
-- ---------------------------------------------------------------------------

update package_version set card_blob_key = null
where package_id in (select id from animation_package where look in ('crimson', 'noir'));

update animation_package set look = case look
    when 'crimson' then 'classic'
    when 'noir'    then 'graphite'
    else look
end
where look in ('crimson', 'noir');
