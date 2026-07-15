/*
 *  Licensed to GraphHopper GmbH under one or more contributor
 *  license agreements. See the NOTICE file distributed with this work for
 *  additional information regarding copyright ownership.
 *
 *  GraphHopper GmbH licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except in
 *  compliance with the License. You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

 package com.graphhopper.routing.util.parsers;

 import com.graphhopper.reader.ReaderWay;
 import com.graphhopper.routing.ev.DecimalEncodedValue;
 import com.graphhopper.routing.ev.EdgeIntAccess;
 import com.graphhopper.storage.IntsRef;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 
 public class RoadRiskParser implements TagParser {
 
     private static final Logger logger = LoggerFactory.getLogger(RoadRiskParser.class);
 
     private final DecimalEncodedValue roadRiskEnc;
     private static final double DEFAULT_RISK = 0.5; // Middle value as default
 
     public RoadRiskParser(DecimalEncodedValue roadRiskEnc) {
         this.roadRiskEnc = roadRiskEnc;
     }
 
     @Override
     public void handleWayTags(int edgeId, EdgeIntAccess edgeIntAccess, ReaderWay way, IntsRef relationFlags) {
         String roadRiskTag = way.getTag("road_risk");
         // Store the crash-risk value exactly as tagged (higher = more dangerous).
         // Missing or unparseable tag -> neutral default.
         double roadRiskValue = DEFAULT_RISK;

         if (roadRiskTag != null) {
             try {
                 // Clamp into [0, 1]; the tag is already crash risk, so no inversion.
                 roadRiskValue = Math.max(0, Math.min(1, Double.parseDouble(roadRiskTag)));
             } catch (NumberFormatException e) {
                 logger.warn("Error parsing road_risk tag: {}. Using default.", roadRiskTag);
             }
         }

         roadRiskEnc.setDecimal(false, edgeId, edgeIntAccess, roadRiskValue);
     }
 }