/*
 * Copyright 2024 Lars Skaug
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


 package com.graphhopper.routing.ev;

 import com.graphhopper.util.Helper;
 
 public enum RoadRisk {
     HIGH, MEDIUM, LOW;  // Define the enum values
 
     public static final String KEY = "road_risk";  // Update the key to reflect the new enum's purpose
 
     // Method to create an EnumEncodedValue for RoadRisk
     public static EnumEncodedValue<RoadRisk> create() {
         return new EnumEncodedValue<>(KEY, RoadRisk.class);
     }
 
     @Override
     public String toString() {
         // Convert the enum value to lowercase string
         return Helper.toLowerCase(super.toString());
     }
 
     // Method to find a RoadRisk value by its name
     public static RoadRisk find(String name) {
         if (name == null || name.isEmpty())
             return MEDIUM;  // Default to MEDIUM if the input is null or empty
 
         try {
             // Attempt to match the input string to an enum value
             return RoadRisk.valueOf(Helper.toUpperCase(name));
         } catch (IllegalArgumentException ex) {
             return MEDIUM;  // Return MEDIUM if the input doesn't match any enum value
         }
     }
 }
 
