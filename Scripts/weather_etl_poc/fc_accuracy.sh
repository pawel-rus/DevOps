#!/bin/bash
# This script compares the forecasted temperature with the current temperature and evaluates the accuracy of the forecast.
# Extract forecasted temp from yesterday (2nd last line, 7th column)
yesterday_fc=$(tail -2 rx_poc.log | head -1 | awk -F '|' '{print $7}' | xargs)
# Extract today's observed temp (last line, 6th column)
today_temp=$(tail -1 rx_poc.log | awk -F '|' '{print $6}' | xargs)
accuracy=$(($yesterday_fc-$today_temp))

abs_accuracy=${accuracy#-}
# Determine accuracy range based on thresholds
if [ "$abs_accuracy" -le 1 ]
then
    accuracy_range="excellent"
elif [ "$abs_accuracy" -le 2 ]
then
    accuracy_range="good"
elif [ "$abs_accuracy" -le 3 ]
then
    accuracy_range="fair"
elif [ "$abs_accuracy" -le 4 ]
then
    accuracy_range="poor"
else
    accuracy_range="very poor"
fi

echo "The forecasted temperature yesterday was: $yesterday_fc °C."
echo "The current temperature today is: $today_temp °C."
echo "The accuracy of the forecast is: $accuracy °C. This is $accuracy_range."

# generate the log file
year=$( tail -1 rx_poc.log | awk -F '|' '{print $2}' | xargs)
month=$( tail -1 rx_poc.log | awk -F '|' '{print $3}' | xargs)
day=$( tail -1 rx_poc.log | awk -F '|' '{print $4}' | xargs)
log_file="historical_fc_accuracy.tsv"
if [ ! -f "$log_file" ]; then
    header="Year\tMonth\tDay\tCurrent Temp (°C)\tForecasted Temp (°C)\tAccuracy (°C)\tAccuracy Range"
    echo -e "$header" > "$log_file"
    echo "Log file created."
fi
# Append the data to the log file
echo -e "$year\t$month\t$day\t$today_temp\t$yesterday_fc\t$accuracy\t$accuracy_range" >> historical_fc_accuracy.tsv