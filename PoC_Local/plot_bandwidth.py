import matplotlib.pyplot as plt
import numpy as np
import re
import os

# Define the log files and their labels
log_files = {
    "Full Res Stream": r"c:\Users\bolty\Desktop\Hackaton\AI_full_res_stream\log.txt",
    "Variable Crop": r"c:\Users\bolty\Desktop\Hackaton\AI_on_receiver_animals\log.txt",
    "Alpha Mask Edge": r"c:\Users\bolty\Desktop\Hackaton\AI_on_receiver_animals_alpha_edge\log.txt"
}

# Regex to extract bandwidth values
pattern = re.compile(r"Bandwidth:\s+([\d.]+)\s+Mbps")

data = {}

for label, filepath in log_files.items():
    bandwidths = []
    if os.path.exists(filepath):
        with open(filepath, 'r') as f:
            for line in f:
                match = pattern.search(line)
                if match:
                    bandwidths.append(float(match.group(1)))
    data[label] = bandwidths

# Plotting
plt.figure(figsize=(12, 6))

colors = {
    "Full Res Stream": "red",
    "Variable Crop": "blue",
    "Alpha Mask Edge": "green"
}

for label, bandwidths in data.items():
    if not bandwidths:
        continue
    
    # Calculate stats
    mean_val = np.mean(bandwidths)
    std_val = np.std(bandwidths)
    
    # Plot line
    plt.plot(bandwidths, label=f"{label} (Mean: {mean_val:.2f}, STD: {std_val:.2f})", color=colors[label], alpha=0.7)
    
    # Draw Mean line
    plt.axhline(mean_val, color=colors[label], linestyle='dashed', linewidth=1)
    
    # Fill between STD
    plt.fill_between(range(len(bandwidths)), mean_val - std_val, mean_val + std_val, color=colors[label], alpha=0.1)

plt.title("Bandwidth Usage Comparison")
plt.xlabel("Time (seconds)")
plt.ylabel("Bandwidth (Mbps)")
plt.legend(loc="upper left", bbox_to_anchor=(1, 1))
plt.grid(True, linestyle='--', alpha=0.6)
plt.tight_layout()

output_path = r"c:\Users\bolty\Desktop\Hackaton\bandwidth_comparison.png"
plt.savefig(output_path, dpi=300)
print(f"Graph saved to {output_path}")
