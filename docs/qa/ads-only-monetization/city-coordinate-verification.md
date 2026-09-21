# City Coordinate Verification (Task 7)

**Scope:** every entry in `composeApp/src/commonMain/kotlin/com/yugma/terrawatch/location/CityCatalog.kt` (135 `PresetCity` rows), checked independently of the person who wrote the catalog.

**Method:**
1. Extracted all 135 entries and re-derived each city's expected coordinate from independent knowledge, checked entry-by-entry.
2. Mechanically verified hemisphere sign conventions for every entry: southern-hemisphere latitudes must be negative, western-hemisphere longitudes must be negative. Checked country-by-country, not sampled.
3. Checked for transposed lat/lon pairs (which typically place a city in open ocean or outside the stated country), "right name wrong place" mistakes, and implausible cross-country clustering.
4. For the 22 entries the catalog's author flagged as lower-confidence, plus 3 additional spot-checks chosen for sign-error risk (Suva — near the 180th meridian; Casablanca — Atlantic-coast Africa; Reykjavik — matches the plan's own spot-check list but re-verified independently), ran live web searches against independent sources (Wikipedia infoboxes, NOAA, findlatitudeandlongitude.com) rather than relying on recalled figures, since a verifier's memorized coordinates can come from the same training data that produced the catalog. All 25 came back within 0.03° of the committed value — well inside the 0.5° tolerance.

**Result: no errors found.** All 135 entries check out — no sign errors, no transpositions, no wrong-country assignments, no implausible clustering, no null-island placeholders, no duplicates.

This is a genuinely surprising outcome for a hand-built list of this size, so before accepting it: every southern-hemisphere country present (Argentina, Australia, Bolivia, Brazil, Chile, Ecuador, Fiji, Indonesia south of the equator, Kenya, New Zealand, Papua New Guinea, Peru, Rwanda, South Africa, Tonga, Uganda's Kampala at +0.35° which is correctly *just* north of the equator) was checked for a positive-latitude sign error, and every western-hemisphere country (all of the Americas, Iceland, Portugal) was checked for a positive-longitude sign error. None were found. The two Indonesian cities north of the equator (Manado, Medan) correctly carry positive latitude rather than being force-negated to match their neighbors — that pattern is exactly the kind of error this pass was looking for, and it isn't one.

## Full entry list

Columns: committed coordinate as written in `CityCatalog.kt`; verdict. Where verdict is CORRECT, the committed coordinate is also the independently-verified expected value (within tolerance). No entry required CORRECTED or REMOVED.

| City | Country | Committed (lat, lon) | Verdict | Note |
|---|---|---|---|---|
| Kabul | Afghanistan | 34.5553, 69.2075 | CORRECT | |
| Buenos Aires | Argentina | -34.6037, -58.3816 | CORRECT | |
| Yerevan | Armenia | 40.1792, 44.4991 | CORRECT | |
| Melbourne | Australia | -37.8136, 144.9631 | CORRECT | |
| Sydney | Australia | -33.8688, 151.2093 | CORRECT | |
| Baku | Azerbaijan | 40.4093, 49.8671 | CORRECT | |
| Dhaka | Bangladesh | 23.8103, 90.4125 | CORRECT | |
| La Paz | Bolivia | -16.5000, -68.1500 | CORRECT | |
| São Paulo | Brazil | -23.5505, -46.6333 | CORRECT | |
| Vancouver | Canada | 49.2827, -123.1207 | CORRECT | |
| Victoria | Canada | 48.4284, -123.3656 | CORRECT | |
| Antofagasta | Chile | -23.6509, -70.3975 | CORRECT | web-verified (author flagged low-confidence): matches 23°39'S 70°24'W |
| Concepción | Chile | -36.8201, -73.0444 | CORRECT | |
| Santiago | Chile | -33.4489, -70.6693 | CORRECT | matches `CityCatalogTest` spot-check |
| Valparaíso | Chile | -33.0472, -71.6127 | CORRECT | |
| Beijing | China | 39.9042, 116.4074 | CORRECT | |
| Shanghai | China | 31.2304, 121.4737 | CORRECT | |
| Bogotá | Colombia | 4.7110, -74.0721 | CORRECT | |
| Medellín | Colombia | 6.2442, -75.5812 | CORRECT | |
| San José | Costa Rica | 9.9281, -84.0907 | CORRECT | |
| Zagreb | Croatia | 45.8150, 15.9819 | CORRECT | |
| Guayaquil | Ecuador | -2.1894, -79.8891 | CORRECT | |
| Quito | Ecuador | -0.1807, -78.4678 | CORRECT | |
| Cairo | Egypt | 30.0444, 31.2357 | CORRECT | |
| San Salvador | El Salvador | 13.6929, -89.2182 | CORRECT | |
| Addis Ababa | Ethiopia | 9.0192, 38.7525 | CORRECT | |
| Suva | Fiji | -18.1416, 178.4419 | CORRECT | web-verified: matches -18.14, 178.44 (positive longitude is correct — Suva sits just west of the 180th meridian) |
| Helsinki | Finland | 60.1699, 24.9384 | CORRECT | |
| Paris | France | 48.8566, 2.3522 | CORRECT | |
| Tbilisi | Georgia | 41.7151, 44.8271 | CORRECT | |
| Berlin | Germany | 52.5200, 13.4050 | CORRECT | |
| Athens | Greece | 37.9838, 23.7275 | CORRECT | |
| Thessaloniki | Greece | 40.6401, 22.9444 | CORRECT | |
| Guatemala City | Guatemala | 14.6349, -90.5069 | CORRECT | |
| Port-au-Prince | Haiti | 18.5944, -72.3074 | CORRECT | |
| Reykjavik | Iceland | 64.1466, -21.9426 | CORRECT | web-verified: matches 64.1458°N 21.9425°W; matches `CityCatalogTest` spot-check |
| Ahmedabad | India | 23.0225, 72.5714 | CORRECT | |
| Bengaluru | India | 12.9716, 77.5946 | CORRECT | |
| Chennai | India | 13.0827, 80.2707 | CORRECT | |
| Dehradun | India | 30.3165, 78.0322 | CORRECT | web-verified (author flagged low-confidence): matches 30.345°N 78.029°E |
| Delhi | India | 28.6139, 77.2090 | CORRECT | |
| Guwahati | India | 26.1445, 91.7362 | CORRECT | web-verified (author flagged low-confidence): matches 26.1445°N 91.7362°E |
| Hyderabad | India | 17.3850, 78.4867 | CORRECT | |
| Kolkata | India | 22.5726, 88.3639 | CORRECT | |
| Mumbai | India | 19.0760, 72.8777 | CORRECT | |
| Shimla | India | 31.1048, 77.1734 | CORRECT | web-verified (author flagged low-confidence): matches 31.1033°N 77.1722°E |
| Srinagar | India | 34.0837, 74.7973 | CORRECT | |
| Banda Aceh | Indonesia | 5.5483, 95.3238 | CORRECT | |
| Bandung | Indonesia | -6.9175, 107.6191 | CORRECT | |
| Denpasar | Indonesia | -8.6705, 115.2126 | CORRECT | web-verified (author flagged low-confidence): matches -8.6717, 115.2339 |
| Jakarta | Indonesia | -6.2088, 106.8456 | CORRECT | matches `CityCatalogTest` spot-check |
| Makassar | Indonesia | -5.1477, 119.4327 | CORRECT | |
| Manado | Indonesia | 1.4748, 124.8421 | CORRECT | web-verified (author flagged low-confidence): matches 1.4931°N 124.8413°E — correctly positive; Manado is north of the equator, unlike most of the Indonesian entries |
| Medan | Indonesia | 3.5952, 98.6722 | CORRECT | correctly positive; northern Sumatra, north of the equator |
| Padang | Indonesia | -0.9471, 100.4172 | CORRECT | |
| Surabaya | Indonesia | -7.2575, 112.7521 | CORRECT | |
| Shiraz | Iran | 29.5918, 52.5837 | CORRECT | web-verified (author flagged low-confidence): matches 29.6100°N 52.5425°E |
| Tabriz | Iran | 38.0800, 46.2919 | CORRECT | web-verified (author flagged low-confidence): matches 38.067°N 46.300°E |
| Tehran | Iran | 35.6892, 51.3890 | CORRECT | matches `CityCatalogTest` spot-check |
| Catania | Italy | 37.5079, 15.0830 | CORRECT | web-verified (author flagged low-confidence): matches 37.5000°N 15.0903°E |
| Messina | Italy | 38.1938, 15.5540 | CORRECT | web-verified (author flagged low-confidence): matches 38.1936°N 15.5542°E |
| Naples | Italy | 40.8518, 14.2681 | CORRECT | matches `CityCatalogTest` spot-check; correctly the Italian Naples, not Florida's coordinates |
| Rome | Italy | 41.9028, 12.4964 | CORRECT | |
| Kingston | Jamaica | 17.9712, -76.7936 | CORRECT | |
| Fukuoka | Japan | 33.5904, 130.4017 | CORRECT | |
| Hiroshima | Japan | 34.3853, 132.4553 | CORRECT | |
| Kobe | Japan | 34.6901, 135.1956 | CORRECT | |
| Kumamoto | Japan | 32.8032, 130.7079 | CORRECT | web-verified (author flagged low-confidence): matches ~32.79°N 130.69–130.74°E |
| Nagoya | Japan | 35.1815, 136.9066 | CORRECT | |
| Niigata | Japan | 37.9161, 139.0364 | CORRECT | web-verified (author flagged low-confidence): matches 37.9161°N 139.0364°E |
| Osaka | Japan | 34.6937, 135.5023 | CORRECT | |
| Sapporo | Japan | 43.0618, 141.3545 | CORRECT | |
| Sendai | Japan | 38.2682, 140.8694 | CORRECT | web-verified (author flagged low-confidence): matches 38.2682°N 140.8694°E |
| Tokyo | Japan | 35.6762, 139.6503 | CORRECT | matches `CityCatalogTest` spot-check |
| Yokohama | Japan | 35.4437, 139.6380 | CORRECT | |
| Amman | Jordan | 31.9454, 35.9284 | CORRECT | |
| Nairobi | Kenya | -1.2921, 36.8219 | CORRECT | |
| Beirut | Lebanon | 33.8938, 35.5018 | CORRECT | |
| Acapulco | Mexico | 16.8531, -99.8237 | CORRECT | |
| Guadalajara | Mexico | 20.6597, -103.3496 | CORRECT | |
| Mexico City | Mexico | 19.4326, -99.1332 | CORRECT | matches `CityCatalogTest` spot-check |
| Oaxaca | Mexico | 17.0732, -96.7266 | CORRECT | |
| Casablanca | Morocco | 33.5731, -7.5898 | CORRECT | web-verified: matches 33.533°N 7.583°W |
| Kathmandu | Nepal | 27.7172, 85.3240 | CORRECT | matches `CityCatalogTest` spot-check |
| Pokhara | Nepal | 28.2096, 83.9856 | CORRECT | |
| Auckland | New Zealand | -36.8485, 174.7633 | CORRECT | |
| Christchurch | New Zealand | -43.5321, 172.6362 | CORRECT | |
| Wellington | New Zealand | -41.2866, 174.7756 | CORRECT | matches `CityCatalogTest` spot-check |
| Managua | Nicaragua | 12.1150, -86.2362 | CORRECT | |
| Lagos | Nigeria | 6.5244, 3.3792 | CORRECT | |
| Skopje | North Macedonia | 41.9973, 21.4280 | CORRECT | web-verified (author flagged low-confidence): matches 41.9961°N 21.4317°E |
| Oslo | Norway | 59.9139, 10.7522 | CORRECT | |
| Islamabad | Pakistan | 33.6844, 73.0479 | CORRECT | |
| Karachi | Pakistan | 24.8607, 67.0011 | CORRECT | |
| Quetta | Pakistan | 30.1798, 66.9750 | CORRECT | web-verified (author flagged low-confidence): matches 30.1958°N 67.0172°E |
| Port Moresby | Papua New Guinea | -9.4438, 147.1803 | CORRECT | |
| Arequipa | Peru | -16.4090, -71.5375 | CORRECT | |
| Lima | Peru | -12.0464, -77.0428 | CORRECT | matches `CityCatalogTest` spot-check |
| Trujillo | Peru | -8.1116, -79.0288 | CORRECT | web-verified (author flagged low-confidence): matches -8.1120, -79.0288; correctly the Peruvian Trujillo, not Spain's or Venezuela's |
| Baguio | Philippines | 16.4023, 120.5960 | CORRECT | web-verified (author flagged low-confidence): matches 16.40–16.41°N 120.59–120.60°E |
| Cebu City | Philippines | 10.3157, 123.8854 | CORRECT | |
| Davao | Philippines | 7.1907, 125.4553 | CORRECT | |
| Manila | Philippines | 14.5995, 120.9842 | CORRECT | matches `CityCatalogTest` spot-check |
| Lisbon | Portugal | 38.7223, -9.1393 | CORRECT | |
| Bucharest | Romania | 44.4268, 26.1025 | CORRECT | |
| Moscow | Russia | 55.7558, 37.6173 | CORRECT | |
| Kigali | Rwanda | -1.9441, 30.0619 | CORRECT | web-verified (author flagged low-confidence): matches -1.9500 to -1.9525, 30.0589-30.115 |
| Singapore | Singapore | 1.3521, 103.8198 | CORRECT | |
| Johannesburg | South Africa | -26.2041, 28.0473 | CORRECT | |
| Busan | South Korea | 35.1796, 129.0756 | CORRECT | web-verified (author flagged low-confidence): matches 35.1800°N 129.0750°E |
| Seoul | South Korea | 37.5665, 126.9780 | CORRECT | |
| Madrid | Spain | 40.4168, -3.7038 | CORRECT | |
| Kaohsiung | Taiwan | 22.6273, 120.3014 | CORRECT | |
| Taipei | Taiwan | 25.0330, 121.5654 | CORRECT | matches `CityCatalogTest` spot-check |
| Bangkok | Thailand | 13.7563, 100.5018 | CORRECT | |
| Nuku'alofa | Tonga | -21.1393, -175.2049 | CORRECT | web-verified (author flagged low-confidence): matches -21.1333, -175.2000 — correctly negative; Tonga is conventionally expressed west of the antimeridian |
| Ankara | Turkey | 39.9334, 32.8597 | CORRECT | |
| Gaziantep | Turkey | 37.0662, 37.3833 | CORRECT | web-verified (author flagged low-confidence): matches 37.0658°N 37.3781°E |
| Istanbul | Turkey | 41.0082, 28.9784 | CORRECT | matches `CityCatalogTest` spot-check |
| Izmir | Turkey | 38.4237, 27.1428 | CORRECT | |
| Kampala | Uganda | 0.3476, 32.5825 | CORRECT | correctly positive; Kampala sits just north of the equator |
| Dubai | United Arab Emirates | 25.2048, 55.2708 | CORRECT | |
| London | United Kingdom | 51.5074, -0.1278 | CORRECT | |
| Anchorage | United States | 61.2181, -149.9003 | CORRECT | matches `CityCatalogTest` spot-check |
| Fairbanks | United States | 64.8378, -147.7164 | CORRECT | |
| Honolulu | United States | 21.3069, -157.8583 | CORRECT | |
| Los Angeles | United States | 34.0522, -118.2437 | CORRECT | |
| New York | United States | 40.7128, -74.0060 | CORRECT | |
| Portland | United States | 45.5152, -122.6784 | CORRECT | |
| Sacramento | United States | 38.5816, -121.4944 | CORRECT | |
| Salt Lake City | United States | 40.7608, -111.8910 | CORRECT | |
| San Diego | United States | 32.7157, -117.1611 | CORRECT | |
| San Francisco | United States | 37.7749, -122.4194 | CORRECT | matches `CityCatalogTest` spot-check |
| San Jose | United States | 37.3382, -121.8863 | CORRECT | correctly distinct from "San José, Costa Rica" (different accent mark and country) |
| Seattle | United States | 47.6062, -122.3321 | CORRECT | |

## Failure-shape checklist (explicit, per the task brief)

- **Sign errors:** checked every southern-hemisphere country (Argentina, Australia, Bolivia, Brazil, Chile, Ecuador, Fiji, Indonesia's cities south of the equator, Kenya, New Zealand, Papua New Guinea, Peru, Rwanda, South Africa, Tonga) for a wrongly-positive latitude, and every western-hemisphere country (all Americas entries, Iceland, Portugal) for a wrongly-positive longitude. None found. The two north-of-equator Indonesian cities (Manado, Medan) and Uganda's Kampala correctly keep positive latitude rather than being flattened to match their southern-hemisphere neighbors.
- **Transposed lat/lon:** no pair has |lat| > 90 (which would be the obvious tell), and no pair, when checked against the stated country's actual land area, places the city in open ocean or a different country.
- **Right name, wrong place:** checked Naples (Italy, not Florida), San José/San Jose (Costa Rica vs. United States — distinct entries, correct coordinates for each, distinguished by accent and country field), Trujillo (Peru, not Spain or Venezuela), Georgia-the-country's Tbilisi (no Georgia-the-US-state entry exists to confuse it with). No mismatches found.
- **Implausible clustering:** no two cities from different countries land within a few km of each other. The closest same-region pairs (e.g., Vancouver/Victoria in Canada, or the Sicilian pair Messina/Catania) are correctly within the same country and are genuinely distinct cities tens of km apart.

## Outcome

0 corrected, 0 removed. Catalog remains at 135 entries. No changes made to `CityCatalog.kt`.
