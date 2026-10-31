-- balatro-dualscreen-thor -- hand earned achievements to the Steam sync.
--
-- With no luasteam, vanilla still records every unlock in
-- G.SETTINGS.ACHIEVEMENTS_EARNED (common_events.lua:1727), keyed by internal
-- name. Only the game knows each one's Steam name (steamid, line 1644), so the
-- earned set is written out as Steam names for the Java side to submit.

local achievements = {}

local FILE = "steam_achievements.txt"
local last, orig_fetch

local function write()
    local earned = G.SETTINGS and G.SETTINGS.ACHIEVEMENTS_EARNED
    if not earned then return end
    -- Built lazily by vanilla too (common_events.lua:1725); without luasteam it only reads settings.
    if not G.ACHIEVEMENTS then orig_fetch() end
    local ids = {}
    for k in pairs(earned) do
        local a = G.ACHIEVEMENTS[k]
        if a and a.steamid then ids[#ids + 1] = a.steamid end
    end
    table.sort(ids)
    local text = table.concat(ids, "\n")
    if text ~= last then
        last = text
        love.filesystem.write(FILE, text)
    end
end

function achievements.install()
    if type(fetch_achievements) ~= "function" or not (Game and Game.save_progress) then
        DS.log("achievements: hooks missing; Steam achievements off")
        return
    end
    orig_fetch = fetch_achievements
    function fetch_achievements(...)
        local r = orig_fetch(...)
        pcall(write)
        return r
    end
    -- unlock_achievement saves progress right after recording (common_events.lua:1728).
    local orig_save = Game.save_progress
    function Game:save_progress(...)
        local r = orig_save(self, ...)
        pcall(write)
        return r
    end
end

return achievements
