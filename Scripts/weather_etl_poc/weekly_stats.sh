#!/bin/bash 
## This script generates weekly statistics from the historical_fc_accuracy.tsv file.

# Load last 7 accuracy values (excluding header)
mapfile -t week_fc < <(tail -n +2 historical_fc_accuracy.tsv | tail -n 7 | awk -F '\t' '{print $6}')
echo "Weekly forecast errors: ${week_fc[*]}"

# Convert all values to absolute integers
abs_errors=()
for val in "${week_fc[@]}"; do
  abs_val=${val#-}  # Remove leading minus if present
  abs_errors+=("$abs_val")
done

# Find min and max
min=${abs_errors[0]}
max=${abs_errors[0]}

for val in "${abs_errors[@]}"; do
  if (( val < min )); then
    min=$val
  fi
  if (( val > max )); then
    max=$val
  fi
done

# Display results
echo "Minimum forecast error this week: $min °C"
echo "Maximum forecast error this week: $max °C"