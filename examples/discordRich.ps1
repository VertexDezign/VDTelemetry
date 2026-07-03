# Small powershell script using https://github.com/potatoqualitee/discordrpc to display some of the exported data in discord
# The above mentioned utilty must be installed
# needs to be global for the timer update
function global:Get-CurrentlyPlaying
{
    # Load XML content from a file
    $xmlPath = "$env:USERPROFILE\Documents\My Games\FarmingSimulator2025\vdTelemetry.xml"
    $xmlContent = [xml](Get-Content -Path $xmlPath)

    # Initialize variable for speed output
    $speedOutput = $null
    $details = $null

    # Check if the <vehicle> node exists
    if ($xmlContent.VDT.vehicle)
    {
        # Navigate to the <speed> node and check if it exists
        $speedNode = $xmlContent.VDT.vehicle.speed
        # Extract the speed value and the unit attribute
        $speedValue = $speedNode.'#text'  # Get text value of the <speed> node
        $speedUnit = $speedNode.unit      # Get the unit attribute value of <speed>

        # Combine them into the desired format
        $speedOutput = "$speedValue $speedUnit"

        $name = $xmlContent.VDT.vehicle.name
        $details = "Driving $name"
    }
    else
    {
        $details = "Standing around"
        $speedOutput = "..."
    }
    [pscustomobject]@{
        Details = $details
        State = $speedOutput
    }
}
$playing = Get-CurrentlyPlaying
$params = @{
    applicationID = "1310313330719592508"
    LargeImageKey = "fs25"
    Details = $playing.Details
    State = $playing.State
    Start = "Now"
    TimerRefresh = 2
    UpdateScript = {
        $playing = global:Get-CurrentlyPlaying
        if (-not $playing)
        {
            Stop-DSClient
        }

        $params = @{
            Details = $playing.Details
            State = $playing.State
        }
        Update-DSRichPresence @params
    }
}

Start-DSClient @params