#!/usr/bin/env python3
"""Writes reference-loads.csv, the independent reference for PmcCalculatorTest (#36).

Written separately from the Kotlin PmcCalculator, straight from the published definitions, so the test is not
checking the calculator against its own output:

- Fitness (CTL) and Fatigue (ATL): exponentially weighted moving averages with time constants 42 and 7 days,
  alpha = 1 - e^(-1/tau), seeded at 0 the day before the first day.
- Form (TSB): yesterday's CTL minus yesterday's ATL (0 on the first day).
- Uncoupled ACWR: mean load of the last 7 days divided by the mean load of the 28 days before them; empty until
  day 34 and when the chronic mean is 0.
- Foster's Monotony: 7-day mean / 7-day sample standard deviation; Strain: 7-day sum x Monotony; empty before
  day 6 and when the standard deviation is 0.

Run from this directory: python3 generate_reference_loads.py > reference-loads.csv
"""

import math
import statistics

# 63 days: a build, rest days, a hard block, a taper and a final week off; day 0 is the first scored day.
LOADS = [
    55, 0, 72, 40, 0, 120, 65,
    0, 80, 45, 60, 0, 150, 70,
    35, 0, 95, 50, 0, 180, 60,
    0, 110, 75, 85, 0, 210, 40,
    0, 0, 0, 30, 0, 90, 25,
    60, 0, 130, 90, 100, 0, 240,
    80, 0, 140, 120, 0, 260, 55,
    0, 70, 0, 50, 0, 100, 20,
    0, 0, 0, 0, 0, 0, 0,
]

alpha_ctl = 1 - math.exp(-1 / 42)
alpha_atl = 1 - math.exp(-1 / 7)

print("day,load,ctl,atl,tsb,acwr,monotony,strain")
ctl = atl = 0.0
for d, load in enumerate(LOADS):
    tsb = ctl - atl
    ctl = ctl + alpha_ctl * (load - ctl)
    atl = atl + alpha_atl * (load - atl)

    acwr = ""
    if d >= 34:
        acute = statistics.mean(LOADS[d - 6 : d + 1])
        chronic = statistics.mean(LOADS[d - 34 : d - 6])
        if chronic != 0:
            acwr = repr(acute / chronic)

    monotony = strain = ""
    if d >= 6:
        week = LOADS[d - 6 : d + 1]
        sd = statistics.stdev(week)
        if sd != 0:
            monotony_value = statistics.mean(week) / sd
            monotony = repr(monotony_value)
            strain = repr(sum(week) * monotony_value)

    print(f"{d},{load},{ctl!r},{atl!r},{tsb!r},{acwr},{monotony},{strain}")
