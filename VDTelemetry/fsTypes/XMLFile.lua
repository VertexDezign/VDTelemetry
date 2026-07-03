---@class XMLFile
XMLFile = {}

---@return XMLFile|nil
function XMLFile.load(objectName, filename, schema)
end

---@return XMLFile|nil
function XMLFile.loadIfExists(objectName, filename, schema)
end

---@return XMLFile|nil
function XMLFile.create(objectName, filename, rootNodeName, schema)
end

function XMLFile:setString(path, value)
end

function XMLFile:setFloat(path, value)
end

function XMLFile:setInt(path, value)
end

function XMLFile:setBool(path, value)
end

function XMLFile:getString(path, default)
end

function XMLFile:getFloat(path, default)
end

function XMLFile:getInt(path, default)
end

function XMLFile:getBool(path, default)
end

function XMLFile:save()
end

function XMLFile:delete()
end