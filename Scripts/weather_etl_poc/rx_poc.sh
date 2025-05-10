#!/bin/bash

# ------------------------------
# This script retrieves current and forecasted temperature for a specific city
# and logs it in a structured format to a local file.
# Intended to be executed automatically once per day (e.g., via crontab):
# Example crontab entry to run at 07:30 every day:
# 30 7 * * * /path/to/this_script.sh
# ------------------------------

city=Krakow
# Fetch weather report from wttr.in
curl -s wttr.in/$city?T --output weather_report

obs_temp=$(grep -m 1 '°' weather_report | grep -Eo -o '[+-]?[0-9]{1,3}' | head -n 1)
fc_temp=$(head -23 weather_report | tail -1 | cut -d 'C' -f1 |  grep -Eo -o '[+-]?[0-9]{1,3}' | head -n 1)

echo "The current temperature in $city: $obs_temp °C."
echo "The forecasted temperature in $city: $fc_temp °C."

day=$(date +'%d')
month=$(date +'%m')
year=$(date +'%Y')
time=$(date +'%H:%M:%S')

# Format the data into a structured table row
record=$(printf "| %-4s | %-5s | %-3s | %-8s | %-17s | %-20s |" "$year" "$month" "$day" "$time" "$obs_temp" "$fc_temp")
log_file="rx_poc.log"
# If log file does not exist, create it with a header and separator line
if [ ! -f "$log_file" ]; then
    header=$(printf "| %-4s | %-5s | %-3s | %-8s | %-17s | %-20s |" "Year" "Month" "Day" "Time" "Current Temp (°C)" "Forecasted Temp (°C)")
    line=$(echo "$header" | sed 's/[^|]/-/g')

    echo -e "$header" > "$log_file"
    echo -e "$line" >> "$log_file"
    echo "Log file created."
fi

echo -e "$record" >> "$log_file"

echo "Running forecast accuracy analysis..."
./fc_accuracy.sh

echo "Running weekly statistics..."
./weekly_stats.sh