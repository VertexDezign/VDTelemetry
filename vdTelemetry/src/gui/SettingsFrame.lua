-- VDTelemetry - Settings menu integration
--
-- @author  Grisu118 - VertexDezign.net
-- @Descripion: Adds the VDTelemetry options (export enabled + write interval) to the in-game
--   General Settings page ("Allgemeine Einstellungen"). Mirrors the clone approach proven in
--   VertexDezign/LiquidManureTransfer: hook InGameMenuSettingsFrame.onFrameOpen, clone the
--   economicDifficulty MultiTextOption plus an existing row/header from gameSettingsLayout, and
--   add the controls to the *general* settings layout (the reference mod targets the Game-Settings
--   tab's gameSettingsLayout; the general tab is a separate layout field on the same frame).
--   Changes apply live via the VDTelemetry setters (which also persist to vdTelemetrySettings.xml).
-- Copyright (C) Grisu118, All Rights Reserved.

VDT = VDT or {}

---@class VDT.SettingsFrame
local SettingsFrame = {}
VDT.SettingsFrame = SettingsFrame

SettingsFrame.MOD_NAME = g_currentModName
SettingsFrame.installed = false
SettingsFrame.elements = {} -- key -> cloned MultiTextOptionElement

-- Selectable write intervals in ms (default is VDTelemetry.DEFAULT_INTERVAL_MS). Presented as a
-- dropdown because FS25 sliders don't fire change events reliably.
SettingsFrame.INTERVAL_OPTIONS = { 100, 250, 500, 1000 }

local function debugger()
  return g_vdTelemetry ~= nil and g_vdTelemetry.debugger or nil
end

---Resolve an l10n key from this mod's environment first, then the global table, then the key.
---@param key string
---@return string
local function getText(key)
  local env = g_i18n.modEnvironments ~= nil and g_i18n.modEnvironments[SettingsFrame.MOD_NAME] or nil
  if env ~= nil and env.texts ~= nil and env.texts[key] ~= nil then
    return env.texts[key]
  end
  return g_i18n:getText(key) or key
end

---@param ms number
---@return string e.g. 100 -> "100 ms (10/s)"
local function formatInterval(ms)
  local hz = 1000 / ms
  local hzText = (hz == math.floor(hz)) and string.format("%d", hz) or string.format("%.1f", hz)
  return string.format("%d ms (%s/s)", ms, hzText)
end

function SettingsFrame.getIntervalTexts()
  if SettingsFrame.intervalTexts == nil then
    SettingsFrame.intervalTexts = {}
    for i, ms in ipairs(SettingsFrame.INTERVAL_OPTIONS) do
      SettingsFrame.intervalTexts[i] = formatInterval(ms)
    end
  end
  return SettingsFrame.intervalTexts
end

function SettingsFrame.getEnabledTexts()
  return { getText("vdt_ui_off"), getText("vdt_ui_on") }
end

---Nearest preset index for a (possibly hand-edited) interval value.
---@param intervalMs number
---@return number state
function SettingsFrame.getStateFromInterval(intervalMs)
  local bestIndex, bestDelta = 1, math.huge
  for i, ms in ipairs(SettingsFrame.INTERVAL_OPTIONS) do
    local delta = math.abs(ms - intervalMs)
    if delta < bestDelta then
      bestIndex, bestDelta = i, delta
    end
  end
  return bestIndex
end

---@param state number
---@return number intervalMs
function SettingsFrame.getIntervalFromState(state)
  return SettingsFrame.INTERVAL_OPTIONS[tonumber(state) or 1] or VDTelemetry.DEFAULT_INTERVAL_MS
end

---@param frame table
---@return table | nil economicDifficulty MultiTextOption to clone from
local function findOptionTemplate(frame)
  if frame.economicDifficulty ~= nil and frame.economicDifficulty.clone ~= nil then
    return frame.economicDifficulty
  end
  return nil
end

---@param frame table
---@return table | nil rowTemplate, table | nil headerTemplate
local function getRowTemplates(frame)
  -- Cloned from the Game-Settings layout (always present, generic settings profiles) even though
  -- the controls are added to the General-Settings layout; element 5 is a row container, 7 a header.
  local layout = frame.gameSettingsLayout
  if layout ~= nil and layout.elements ~= nil then
    return layout.elements[5], layout.elements[7]
  end
  return nil, nil
end

-- Field name of the "Allgemeine Einstellungen" (General Settings) box layout on the frame. The
-- Game-Settings tab is `gameSettingsLayout`; the general tab is one of these candidates.
local GENERAL_LAYOUT_CANDIDATES = { "generalSettingsLayout", "generalGameSettingsLayout", "generalLayout" }

---@param frame table
---@return table | nil layout, string | nil fieldName
local function getTargetLayout(frame)
  for _, name in ipairs(GENERAL_LAYOUT_CANDIDATES) do
    local v = frame[name]
    if type(v) == "table" and type(v.addElement) == "function" then
      return v, name
    end
  end
  return nil, nil
end

-- One-time diagnostic: log every box-layout-like field on the frame so the correct general-settings
-- layout field name can be identified if the candidates above miss.
local dumpedLayouts = false
local function dumpLayouts(frame)
  local d = debugger()
  if d == nil or dumpedLayouts then
    return
  end
  dumpedLayouts = true
  for k, v in pairs(frame) do
    if type(v) == "table" and type(v.addElement) == "function" then
      d:info(
        "[SettingsFrame] layout field: frame.%s (typeName=%s children=%s)",
        tostring(k),
        tostring(v.typeName),
        (v.elements ~= nil) and tostring(#v.elements) or "?"
      )
    end
  end
end

-- Push the current mod state into the GUI controls (on frame open, and whenever we re-open). Uses
-- setState without the force flag, so it does not fire onClickCallback (no feedback loop).
function SettingsFrame.refreshGui()
  local vdt = g_vdTelemetry
  if vdt == nil then
    return
  end
  local elements = SettingsFrame.elements
  if elements.enabled ~= nil then
    elements.enabled:setState(vdt.exportEnabled and 2 or 1)
  end
  if elements.interval ~= nil then
    elements.interval:setState(SettingsFrame.getStateFromInterval(vdt.writeIntervalMs))
  end
end

---Clone one option control into its own titled row container and add it to the layout.
---@param layout table gameSettingsLayout
---@param cloneElement table the cloned MultiTextOption
---@param id string
---@param textId string title l10n key
---@param tooltipId string tooltip l10n key
---@param rowTemplate table an existing row to clone the container/title from
function SettingsFrame.addOptionToLayout(layout, cloneElement, id, textId, tooltipId, rowTemplate)
  cloneElement.id = id

  local tooltip = cloneElement.elements ~= nil and cloneElement.elements[1] or nil
  if tooltip ~= nil then
    tooltip.text = getText(tooltipId)
    tooltip.sourceText = getText(tooltipId)
  end

  local optionTitle = rowTemplate.elements[2]:clone()
  optionTitle.id = id .. "Title"
  optionTitle:applyProfile("fs25_settingsMultiTextOptionTitle", true)
  optionTitle:setText(getText(textId))

  local optionContainer = rowTemplate:clone()
  optionContainer.id = id .. "Container"
  optionContainer:applyProfile("fs25_multiTextOptionContainer", true)

  for key, _ in pairs(optionContainer.elements) do
    optionContainer.elements[key] = nil
  end

  optionContainer:addElement(optionTitle)
  optionContainer:addElement(cloneElement)
  layout:addElement(optionContainer)
end

---onFrameOpen hook body (frame is the InGameMenuSettingsFrame instance). Builds the controls once
---per frame instance, then keeps them in sync with the current mod state.
---@param frame table
function SettingsFrame.initSettingsGui(frame)
  local vdt = g_vdTelemetry
  if vdt == nil or frame == nil or frame.gameSettingsLayout == nil then
    return
  end
  if not vdt:isTelemetryAvailable() then
    return
  end

  local targetLayout = getTargetLayout(frame)
  if targetLayout == nil then
    dumpLayouts(frame)
    if debugger() then
      debugger():warn("[SettingsFrame] General Settings layout not found; controls not added (see layout field dump)")
    end
    return
  end

  -- already added to this frame instance -> just resync the states
  if frame.vdtExportEnabled ~= nil then
    SettingsFrame.refreshGui()
    return
  end

  local rowTemplate, headerTemplate = getRowTemplates(frame)
  local optionTemplate = findOptionTemplate(frame)
  if rowTemplate == nil or headerTemplate == nil or optionTemplate == nil then
    if debugger() then
      debugger():warn("[SettingsFrame] required FS25 GUI templates not found; controls not added")
    end
    return
  end

  -- section header
  local title = headerTemplate:clone()
  title:applyProfile("fs25_settingsSectionHeader", true)
  title:setText(getText("vdt_settings_title"))
  title.focusChangeData = {}
  title.focusId = FocusManager.serveAutoFocusId()
  targetLayout:addElement(title)

  local options = {
    {
      id = "vdtExportEnabled",
      key = "enabled",
      title = "vdt_setting_exportEnabled",
      tooltip = "vdt_setting_exportEnabled_tooltip",
      texts = SettingsFrame.getEnabledTexts(),
    },
    {
      id = "vdtWriteInterval",
      key = "interval",
      title = "vdt_setting_writeInterval",
      tooltip = "vdt_setting_writeInterval_tooltip",
      texts = SettingsFrame.getIntervalTexts(),
    },
  }

  for _, option in ipairs(options) do
    local cloneElement = optionTemplate:clone()
    cloneElement.id = option.id
    cloneElement.target = cloneElement
    cloneElement.texts = option.texts
    cloneElement.onClickCallback = SettingsFrame.onSettingChanged
    cloneElement.buttonLRChange = true

    SettingsFrame.addOptionToLayout(targetLayout, cloneElement, option.id, option.title, option.tooltip, rowTemplate)

    SettingsFrame.elements[option.key] = cloneElement
    frame[option.id] = cloneElement
  end

  SettingsFrame.refreshGui()
  targetLayout:invalidateLayout()

  if debugger() then
    debugger():debug("[SettingsFrame] added export + interval controls to gameSettingsLayout")
  end
end

---Change callback shared by both controls. `element` is the control (target == self), `state` its
---new 1-based index. Dispatches to the matching live setter.
---@param element table
---@param state number
function SettingsFrame.onSettingChanged(element, state)
  local vdt = g_vdTelemetry
  local elements = SettingsFrame.elements
  if vdt == nil then
    return
  end

  if element == elements.enabled then
    vdt:setExportEnabled((state or element.state) == 2)
  elseif element == elements.interval then
    vdt:setWriteIntervalMs(SettingsFrame.getIntervalFromState(state or element.state))
  end
end

---Install the onFrameOpen hook. Safe to call multiple times.
function SettingsFrame.install()
  if SettingsFrame.installed then
    return
  end
  if InGameMenuSettingsFrame == nil or InGameMenuSettingsFrame.onFrameOpen == nil then
    if debugger() then
      debugger():error("[SettingsFrame] InGameMenuSettingsFrame not available; settings UI not installed")
    end
    return
  end
  InGameMenuSettingsFrame.onFrameOpen =
    Utils.appendedFunction(InGameMenuSettingsFrame.onFrameOpen, SettingsFrame.initSettingsGui)
  SettingsFrame.installed = true
end

-- Install at source time (covers the main-menu settings too); loadMap re-calls install() as a
-- fallback in case the frame class wasn't loaded yet here. Both are guarded by `installed`.
SettingsFrame.install()
