-- balatro-dualscreen-thor -- controller focus in DISPLAYED positions.
--
-- Vanilla picks the next focus by distance in tile space
-- (Controller:update_focus, controller.lua:1112). Screen-2 nodes keep their
-- vanilla tile positions but are drawn elsewhere, so a screen-1 node can sit
-- "between" two stacked menu buttons and steal the D-pad (the title ace
-- between PLAY and OPTIONS). For the length of the call, screen-2 nodes are
-- moved to where they are displayed and screen-1 nodes are lifted above them,
-- so the panel sits below screen 1 as on the hardware. Lifting screen 1 rather
-- than dropping the panel keeps both under is_node_focusable's floor
-- (controller.lua:1081). The cursor follows its target into that space.

local focusmap = {}

-- Keyed by T, not node: glued children share their major's T table.
local saved, cursor_saved = {}, nil

-- Displayed rect of node n drawn under HASH2 entry e, in panel tile units
-- (panel pixels / panel pixels-per-tile), so the panel spans about one room.
local function displayed(n, e, w, h)
    local T = n.T
    local sc = e.s or 1
    -- Forward of DS.hit_test's inverse: offset, then scale about (cx, cy).
    local x, y = T.x + (e.dx or 0), T.y + (e.dy or 0)
    local cx, cy = e.cx or 0, e.cy or 0
    x, y = cx + (x - cx) * sc, cy + (y - cy) * sc
    local px, py, k = DS.render.tile_to_panel(x, y, w, h)
    if not px or not k or k <= 0 then return nil end
    -- Keep the panel's bottom edge under the focusable floor (controller.lua:1081).
    local base = math.min(0, G.ROOM.T.h - h / k)
    return G.ROOM.T.w / 2 + (px - w / 2) / k, base + py / k, T.w * sc, T.h * sc
end

-- A HASH2 entry is often a whole UIBox; its buttons are the focusables.
local function map_tree(n, e, w, h)
    if not n or not n.T or saved[n.T] then return end
    local x, y, tw, th = displayed(n, e, w, h)
    if x then
        local T = n.T
        saved[T] = { T.x, T.y, T.w, T.h }
        T.x, T.y, T.w, T.h = x, y, tw, th
    end
    if n.UIRoot then map_tree(n.UIRoot, e, w, h) end
    for _, c in ipairs(n.children or {}) do map_tree(c, e, w, h) end
end

local function swap_in(ctrl)
    local w, h = DS.panel_w or 0, DS.panel_h or 0
    if w <= 0 or h <= 0 then return end
    for _, e in ipairs(DS.HASH2) do map_tree(e.n, e, w, h) end
    -- Above the panel's top; anything lower than the floor is unfocusable anyway.
    local lift = 2 * G.ROOM.T.h + 4
    for _, v in pairs(G.MOVEABLES) do
        local T = v.T
        if T and not saved[T] and v ~= G.CURSOR then
            saved[T] = { T.x, T.y, T.w, T.h }
            T.y = T.y - lift
        end
    end
    -- update_focus drops a target the cursor no longer touches (controller.lua:1134).
    local t = ctrl.focused.target
    if t and t.T and saved[t.T] and G.CURSOR then
        cursor_saved = { G.CURSOR.T.x, G.CURSOR.T.y }
        G.CURSOR.T.x = t.T.x + t.T.w / 2 + G.ROOM.T.x
        G.CURSOR.T.y = t.T.y + t.T.h / 2 + G.ROOM.T.y
    end
end

local function swap_out()
    for T, v in pairs(saved) do
        T.x, T.y, T.w, T.h = v[1], v[2], v[3], v[4]
        saved[T] = nil
    end
    if cursor_saved then
        G.CURSOR.T.x, G.CURSOR.T.y = cursor_saved[1], cursor_saved[2]
        cursor_saved = nil
    end
end

function focusmap.install()
    if not (Controller and Controller.update_focus) then
        DS.log("focusmap: Controller:update_focus missing; vanilla navigation")
        return
    end
    local orig = Controller.update_focus
    function Controller:update_focus(dir)
        if not (dir and DS.active and DS.HASH2 and #DS.HASH2 > 0) then
            return orig(self, dir)
        end
        local ok = pcall(swap_in, self)
        local ok2, err = pcall(orig, self, dir)
        swap_out()
        if not ok then DS.log("focusmap: swap failed") end
        if not ok2 then error(err, 0) end
    end
    DS.log("focusmap: navigation follows displayed positions")
end

return focusmap
