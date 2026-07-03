"""Render a clear CuraX systematic diagram for the FYP poster."""
from pathlib import Path

import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch

OUT = Path(__file__).resolve().parent / "poster_systematic_diagram.png"

# Poster-friendly palette
C_ADMIN = "#1F3864"
C_PATIENT = "#2E7D32"
C_CLOUD = "#5B2C83"
C_BOX = "#E65100"
C_BG = "#FFFFFF"
C_MUTED = "#555555"


def box(ax, x, y, w, h, title, lines, face, edge, title_color="white", text_color="#222"):
    patch = FancyBboxPatch(
        (x, y),
        w,
        h,
        boxstyle="round,pad=0.012,rounding_size=0.02",
        linewidth=2,
        edgecolor=edge,
        facecolor=face,
        transform=ax.transAxes,
        zorder=2,
    )
    ax.add_patch(patch)
    ax.text(
        x + w / 2,
        y + h - 0.035,
        title,
        ha="center",
        va="top",
        fontsize=11,
        fontweight="bold",
        color=title_color if face in (C_ADMIN, C_PATIENT, C_CLOUD) else edge,
        transform=ax.transAxes,
        zorder=3,
    )
    line_y = y + h - 0.075
    for line in lines:
        ax.text(
            x + w / 2,
            line_y,
            line,
            ha="center",
            va="top",
            fontsize=8.5,
            color=text_color if face not in (C_ADMIN, C_PATIENT, C_CLOUD) else "#F5F5F5",
            transform=ax.transAxes,
            zorder=3,
        )
        line_y -= 0.028


def arrow(ax, x1, y1, x2, y2, label, color="#333", style="-|>", dy=0):
    arr = FancyArrowPatch(
        (x1, y1),
        (x2, y2),
        arrowstyle=style,
        mutation_scale=12,
        linewidth=1.8,
        color=color,
        transform=ax.transAxes,
        zorder=1,
        connectionstyle="arc3,rad=0.0",
    )
    ax.add_patch(arr)
    if label:
        ax.text(
            (x1 + x2) / 2,
            (y1 + y2) / 2 + dy,
            label,
            ha="center",
            va="center",
            fontsize=7.5,
            color=color,
            fontweight="600",
            bbox=dict(boxstyle="round,pad=0.25", facecolor="white", edgecolor="none", alpha=0.9),
            transform=ax.transAxes,
            zorder=4,
        )


def step(ax, x, y, n, text):
    ax.text(
        x,
        y,
        f"{n}",
        ha="center",
        va="center",
        fontsize=10,
        fontweight="bold",
        color="white",
        bbox=dict(boxstyle="circle,pad=0.35", facecolor=C_CLOUD, edgecolor="white", linewidth=1.5),
        transform=ax.transAxes,
        zorder=5,
    )
    ax.text(x, y - 0.045, text, ha="center", va="top", fontsize=8, color=C_MUTED, transform=ax.transAxes)


fig, ax = plt.subplots(figsize=(12, 6.2), dpi=200)
ax.set_xlim(0, 1)
ax.set_ylim(0, 1)
ax.axis("off")
fig.patch.set_facecolor(C_BG)

# Title
ax.text(0.5, 0.96, "CuraX Systematic Diagram", ha="center", fontsize=18, fontweight="bold", color=C_ADMIN)
ax.text(
    0.5,
    0.915,
    "How data and control move between caregiver, patient app, cloud, and smart medicine box",
    ha="center",
    fontsize=10,
    color=C_MUTED,
)

# Layer labels
for lx, label in [(0.17, "CAREGIVER"), (0.5, "CLOUD"), (0.83, "PATIENT + DEVICE")]:
    ax.text(lx, 0.865, label, ha="center", fontsize=8, fontweight="bold", color="#888")

# Main blocks
box(
    ax, 0.05, 0.58, 0.24, 0.2,
    "Admin Dashboard",
    ["Manage users & medicines", "View alerts & adherence"],
    C_ADMIN, C_ADMIN,
)

box(
    ax, 0.38, 0.58, 0.24, 0.2,
    "Cloud Backend",
    ["REST API · PostgreSQL", "DataBus · Alert scheduler"],
    C_CLOUD, C_CLOUD,
)

box(
    ax, 0.71, 0.62, 0.24, 0.16,
    "Patient Android App",
    ["Reminders · Dose logs", "Default or Standalone"],
    C_PATIENT, C_PATIENT,
)

box(
    ax, 0.71, 0.28, 0.24, 0.22,
    "Smart Medicine Box",
    ["ESP32 controller", "T sensors · 6 servos", "Zone cooling (fans)"],
    "#FFF8E1", C_BOX, title_color=C_BOX,
)

# Standalone note
box(
    ax, 0.05, 0.28, 0.24, 0.16,
    "Standalone mode",
    ["App-only use", "No hardware needed"],
    "#F5F5F5", "#888", title_color="#444", text_color="#555",
)

# Flow arrows
arrow(ax, 0.29, 0.68, 0.38, 0.68, "Setup & sync", C_ADMIN, dy=0.03)
arrow(ax, 0.62, 0.68, 0.71, 0.70, "Schedules & alerts", C_CLOUD, dy=0.03)
arrow(ax, 0.83, 0.62, 0.83, 0.50, "BLE commands", C_PATIENT, dy=0)
arrow(ax, 0.50, 0.58, 0.50, 0.50, "", C_CLOUD)
arrow(ax, 0.50, 0.50, 0.71, 0.39, "Live temperature", C_BOX, dy=0.02, style="-|>")
arrow(ax, 0.71, 0.39, 0.62, 0.58, "Dose logs", C_PATIENT, dy=-0.04)
arrow(ax, 0.38, 0.64, 0.29, 0.64, "Monitor users", C_ADMIN, dy=-0.04)

# Dashed standalone
arr = FancyArrowPatch(
    (0.29, 0.36),
    (0.71, 0.36),
    arrowstyle="-|>",
    mutation_scale=12,
    linewidth=1.5,
    color="#888",
    linestyle=(0, (5, 4)),
    transform=ax.transAxes,
    zorder=1,
)
ax.add_patch(arr)
ax.text(0.5, 0.375, "Local reminders only", ha="center", fontsize=7.5, color="#777", transform=ax.transAxes)

# Numbered workflow strip
ax.text(0.5, 0.205, "Daily workflow", ha="center", fontsize=9, fontweight="bold", color=C_ADMIN)
step(ax, 0.18, 0.155, "1", "Admin sets\nmedicines")
step(ax, 0.38, 0.155, "2", "Cloud stores\nschedule")
step(ax, 0.58, 0.155, "3", "App reminds\npatient")
step(ax, 0.78, 0.155, "4", "Box guides\ndrawer")
step(ax, 0.92, 0.155, "5", "Dose logged\nfor caregiver")

for x1, x2 in [(0.22, 0.34), (0.42, 0.54), (0.62, 0.74), (0.82, 0.88)]:
    arrow(ax, x1, 0.155, x2, 0.155, "", "#999", style="-|>", dy=0)

# Legend
ax.text(0.08, 0.04, "Blue = caregiver path", fontsize=8, color=C_ADMIN, fontweight="600")
ax.text(0.30, 0.04, "Green = patient / BLE path", fontsize=8, color=C_PATIENT, fontweight="600")
ax.text(0.56, 0.04, "Orange = hardware layer", fontsize=8, color=C_BOX, fontweight="600")
ax.text(0.78, 0.04, "Grey dashed = standalone", fontsize=8, color="#777", fontweight="600")

plt.tight_layout(pad=0.4)
fig.savefig(OUT, bbox_inches="tight", facecolor=C_BG, dpi=200)
plt.close()
print(f"Saved {OUT}")
