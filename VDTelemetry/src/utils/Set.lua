-- Define the Set class
---@class Set
Set = {}
Set.__index = Set

-- Constructor for the Set class
function Set:new(list)
  local set = {}
  setmetatable(set, Set)
  set.elements = {}
  if list then
    for _, value in ipairs(list) do
      set.elements[value] = true
    end
  end
  return set
end

-- Method to check if a set contains a specific value
function Set:contains(value)
  return self.elements[value] ~= nil
end

-- Method to add a value to the set
function Set:add(value)
  self.elements[value] = true
end

-- Method to remove a value from the set
function Set:remove(value)
  self.elements[value] = nil
end