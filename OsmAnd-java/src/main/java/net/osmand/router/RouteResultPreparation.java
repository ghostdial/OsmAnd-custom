package net.osmand.router;

import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.hash.TIntHashSet;
import net.osmand.PlatformUtil;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.BinaryMapRouteReaderAdapter.RouteTypeRule;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.osm.MapRenderingTypes;
import net.osmand.render.RenderingRuleSearchRequest;
import net.osmand.render.RenderingRulesStorage;
import net.osmand.render.RenderingRulesStorage.RenderingRulesStorageResolver;
import net.osmand.router.BinaryRoutePlanner.FinalRouteSegment;
import net.osmand.router.BinaryRoutePlanner.RouteSegment;
import net.osmand.router.GeneralRouter.GeneralRouterProfile;
import net.osmand.router.RoutePlannerFrontEnd.RouteCalculationMode;
import net.osmand.router.RouteStatisticsHelper.RouteStatistics;
import net.osmand.util.Algorithms;
import net.osmand.util.MapAlgorithms;
import net.osmand.util.MapUtils;

public class RouteResultPreparation {

	public static boolean PRINT_TO_CONSOLE_ROUTE_INFORMATION_TO_TEST = false;
	public static String PRINT_TO_GPX_FILE = null;
	private static final float TURN_DEGREE_MIN = 45;
	private static final float UNMATCHED_TURN_DEGREE_MINIMUM = 45;
	private static final float SPLIT_TURN_DEGREE_NOT_STRAIGHT = 100;
	public static final int SHIFT_ID = 6;
	protected static final Log LOG = PlatformUtil.getLog(RouteResultPreparation.class);
	public static final String UNMATCHED_HIGHWAY_TYPE = "unmatched";
	/**
	 * Helper method to prepare final result 
	 */
	List<RouteSegmentResult> prepareResult(RoutingContext ctx, FinalRouteSegment finalSegment) throws IOException {
		List<RouteSegmentResult> result  = convertFinalSegmentToResults(ctx, finalSegment);
		prepareResult(ctx, result, false);
		return result;
	}
	
	private static class CombineAreaRoutePoint {
		int x31;
		int y31;
		int originalIndex;
	}

	private void combineWayPointsForAreaRouting(RoutingContext ctx, List<RouteSegmentResult> result) {
		for(int i = 0; i < result.size(); i++) {
			RouteSegmentResult rsr = result.get(i);
			RouteDataObject obj = rsr.getObject();
			boolean area = false;
			if(obj.getPoint31XTile(0) == obj.getPoint31XTile(obj.getPointsLength() - 1) &&
					obj.getPoint31YTile(0) == obj.getPoint31YTile(obj.getPointsLength() - 1)) {
				area = true;
			}
			if(!area || !ctx.getRouter().isArea(obj)) {
				continue;
			}
			List<CombineAreaRoutePoint> originalWay = new ArrayList<CombineAreaRoutePoint>();
			List<CombineAreaRoutePoint> routeWay = new ArrayList<CombineAreaRoutePoint>();
			for(int j = 0;  j < obj.getPointsLength(); j++) {
				CombineAreaRoutePoint pnt = new CombineAreaRoutePoint();
				pnt.x31 = obj.getPoint31XTile(j);
				pnt.y31 = obj.getPoint31YTile(j);
				pnt.originalIndex = j;
				
				originalWay.add(pnt);
				if(j >= rsr.getStartPointIndex() && j <= rsr.getEndPointIndex()) {
					routeWay.add(pnt);
				} else if(j <= rsr.getStartPointIndex() && j >= rsr.getEndPointIndex()) {
					routeWay.add(0, pnt);
				}
			}
			int originalSize = routeWay.size();
			simplifyAreaRouteWay(routeWay, originalWay);
			int newsize = routeWay.size();
			if (routeWay.size() != originalSize) {
				RouteDataObject nobj = new RouteDataObject(obj);
				nobj.pointsX = new int[newsize];
				nobj.pointsY = new int[newsize];
				for (int k = 0; k < newsize; k++) {
					nobj.pointsX[k] = routeWay.get(k).x31;
					nobj.pointsY[k] = routeWay.get(k).y31;
				}
				// in future point names might be used
				nobj.restrictions = null;
				nobj.restrictionsVia = null;
				nobj.pointTypes = null;
				nobj.pointNames = null;
				nobj.pointNameTypes = null;
				RouteSegmentResult nrsr = new RouteSegmentResult(nobj, 0, newsize - 1);
				result.set(i, nrsr);
			}
		}
	}

	private void simplifyAreaRouteWay(List<CombineAreaRoutePoint> routeWay, List<CombineAreaRoutePoint> originalWay) {
		boolean changed = true;
		while (changed) {
			changed = false;
			int connectStart = -1;
			int connectLen = 0;
			double dist = 0;
			int length = routeWay.size() - 1;
			while (length > 0 && connectLen == 0) {
				for (int i = 0; i < routeWay.size() - length; i++) {
					CombineAreaRoutePoint p = routeWay.get(i);
					CombineAreaRoutePoint n = routeWay.get(i + length);
					if (segmentLineBelongsToPolygon(p, n, originalWay)) {
						double ndist = BinaryRoutePlanner.squareRootDist(p.x31, p.y31, n.x31, n.y31);
						if (ndist > dist) {
							ndist = dist;
							connectStart = i;
							connectLen = length;
						}
					}
				}
				length--;
			}
			while (connectLen > 1) {
				routeWay.remove(connectStart + 1);
				connectLen--;
				changed = true;
			}
		}
		
	}

	private boolean segmentLineBelongsToPolygon(CombineAreaRoutePoint p, CombineAreaRoutePoint n,
			List<CombineAreaRoutePoint> originalWay) {
		int intersections = 0;
		int mx = p.x31 / 2 + n.x31 / 2;
		int my = p.y31 / 2 + n.y31 / 2;
		for(int i = 1; i < originalWay.size(); i++) {
			CombineAreaRoutePoint p2 = originalWay.get(i -1);
			CombineAreaRoutePoint n2 = originalWay.get(i);
			if(p.originalIndex != i && p.originalIndex != i - 1) {
				if(n.originalIndex != i && n.originalIndex != i - 1) {
					if(MapAlgorithms.linesIntersect(p.x31, p.y31, n.x31, n.y31, p2.x31, p2.y31, n2.x31, n2.y31)) {
						return false;
					}
				}
			}
			int fx = MapAlgorithms.ray_intersect_x(p2.x31, p2.y31, n2.x31, n2.y31, my);
			if (Integer.MIN_VALUE != fx && mx >= fx) {
				intersections++;
			}
		}
		return intersections % 2 == 1;
	}

	List<RouteSegmentResult> prepareResult(RoutingContext ctx, List<RouteSegmentResult> result, boolean recalculation) throws IOException {
		for (int i = 0; i < result.size(); i++) {
			RouteDataObject road = result.get(i).getObject();
			checkAndInitRouteRegion(ctx, road);
			// "osmand_dp" using for backward compatibility from native lib RoutingConfiguration directionPoints
			if (road.region != null) {
				road.region.findOrCreateRouteType(RoutingConfiguration.DirectionPoint.TAG, RoutingConfiguration.DirectionPoint.DELETE_TYPE);
			}
		}
		combineWayPointsForAreaRouting(ctx, result);
		validateAllPointsConnected(result);
		splitRoadsAndAttachRoadSegments(ctx, result, recalculation);
		for (int i = 0; i < result.size(); i++) {
			filterMinorStops(result.get(i));
		}
		calculateTimeSpeed(ctx, result);
		prepareTurnResults(ctx, result);
		return result;
	}
	
	public RouteSegmentResult filterMinorStops(RouteSegmentResult seg) {
		List<Integer> stops = null;
		boolean plus = seg.getStartPointIndex() < seg.getEndPointIndex();
		int next;

		for (int i = seg.getStartPointIndex(); i != seg.getEndPointIndex(); i = next) {
			next = plus ? i + 1 : i - 1;
			int[] pointTypes = seg.getObject().getPointTypes(i);
			if (pointTypes != null) {
				for (int j = 0; j < pointTypes.length; j++) {
					if (pointTypes[j] == seg.getObject().region.stopMinor) {
						if (stops == null) {
							stops = new ArrayList<>();
						}
						stops.add(i);
					}
				}
			}
		}

		if (stops != null) {
			for (int stop : stops) {
				List<RouteSegmentResult> attachedRoutes = seg.getAttachedRoutes(stop);
				for (RouteSegmentResult attached : attachedRoutes) {
					int attStopPriority = highwaySpeakPriority(attached.getObject().getHighway());
					int segStopPriority = highwaySpeakPriority(seg.getObject().getHighway());
					if (segStopPriority < attStopPriority) {
						seg.getObject().removePointType(stop, seg.getObject().region.stopSign);
						break;
					}
				}
			}
		}
		return seg;
	}

	public void prepareTurnResults(RoutingContext ctx, List<RouteSegmentResult> result) {
		for (int i = 0; i < result.size(); i ++) {
			TurnType turnType = getTurnInfo(result, i, ctx.leftSideNavigation);
			result.get(i).setTurnType(turnType);
		}
		
		determineTurnsToMerge(ctx.leftSideNavigation, result);
		ignorePrecedingStraightsOnSameIntersection(ctx.leftSideNavigation, result);
		justifyUTurns(ctx.leftSideNavigation, result);
		addTurnInfoDescriptions(result);
	}

	protected void ignorePrecedingStraightsOnSameIntersection(boolean leftside, List<RouteSegmentResult> result) {
		//Issue 2571: Ignore TurnType.C if immediately followed by another turn in non-motorway cases, as these likely belong to the very same intersection
		RouteSegmentResult nextSegment = null;
		double distanceToNextTurn = 999999;
		for (int i = result.size() - 1; i >= 0; i--) {
			// Mark next "real" turn
			if (nextSegment != null && nextSegment.getTurnType() != null &&
					nextSegment.getTurnType().getValue() != TurnType.C &&
					!isMotorway(nextSegment)) {
				if (distanceToNextTurn == 999999) {
					distanceToNextTurn = 0;
				}
			}
			RouteSegmentResult currentSegment = result.get(i);
			// Identify preceding goStraights within distance limit and suppress
			if (currentSegment != null) {
				distanceToNextTurn += currentSegment.getDistance();
				if (currentSegment.getTurnType() != null &&
						currentSegment.getTurnType().getValue() == TurnType.C &&
						distanceToNextTurn <= 100) {
					result.get(i).getTurnType().setSkipToSpeak(true);
				} else {
					nextSegment = currentSegment;
					distanceToNextTurn = 999999;
				}
			}
		}
	}

	private void justifyUTurns(boolean leftSide, List<RouteSegmentResult> result) {
		int next;
		for (int i = 1; i < result.size() - 1; i = next) {
			next = i + 1;
			TurnType t = result.get(i).getTurnType();
			// justify turn
			if (t != null) {
				TurnType jt = justifyUTurn(leftSide, result, i, t);
				if (jt != null) {
					result.get(i).setTurnType(jt);
					next = i + 2;
				}
			}
		}
	}

	// decrease speed proportionally from 15ms (50kmh)
	private static final double SLOW_DOWN_SPEED_THRESHOLD = 15;
	// reference speed 30ms (108kmh) - 2ms (7kmh)
	private static final double SLOW_DOWN_SPEED = 2;
	
	public static void calculateTimeSpeed(RoutingContext ctx, List<RouteSegmentResult> result) {
		//for Naismith/Scarf
		boolean usePedestrianHeight = ((((GeneralRouter) ctx.getRouter()).getProfile() == GeneralRouterProfile.PEDESTRIAN) && ((GeneralRouter) ctx.getRouter()).getHeightObstacles());
		double scarfSeconds = 7.92f / ctx.getRouter().getDefaultSpeed();

		for (int i = 0; i < result.size(); i++) {
			RouteSegmentResult rr = result.get(i);
			RouteDataObject road = rr.getObject();
			double distOnRoadToPass = 0;
			double speed = ctx.getRouter().defineVehicleSpeed(road);
			if (speed == 0) {
				speed = ctx.getRouter().getDefaultSpeed();
			} else {
				if (speed > SLOW_DOWN_SPEED_THRESHOLD) {
					speed = speed - (speed / SLOW_DOWN_SPEED_THRESHOLD - 1) * SLOW_DOWN_SPEED;
				}
			}
			boolean plus = rr.getStartPointIndex() < rr.getEndPointIndex();
			int next;
			double distance = 0;

			//for Naismith/Scarf
			float prevHeight = 99999.0f;
			float[] heightDistanceArray = null;
			if (usePedestrianHeight) {
				road.calculateHeightArray();
				heightDistanceArray = road.heightDistanceArray;
			}

			for (int j = rr.getStartPointIndex(); j != rr.getEndPointIndex(); j = next) {
				next = plus ? j + 1 : j - 1;
				double d = measuredDist(road.getPoint31XTile(j), road.getPoint31YTile(j), road.getPoint31XTile(next),
						road.getPoint31YTile(next));
				distance += d;
				double obstacle = ctx.getRouter().defineObstacle(road, j, plus);
				if (obstacle < 0) {
					obstacle = 0;
				}
				distOnRoadToPass += d / speed + obstacle;  //this is time in seconds

				//for Naismith/Scarf
				if (usePedestrianHeight) {
					int heightIndex = 2 * j + 1;
					if (heightDistanceArray != null && heightIndex < heightDistanceArray.length) {
						float height = heightDistanceArray[heightIndex];
						float heightDiff = height - prevHeight;
						if (heightDiff > 0) { // ascent only
							// Naismith/Scarf rule: An ascent adds 7.92 times the hiking time its vertical elevation gain takes to cover horizontally
							// - Naismith original: Add 1 hour per vertical 2000ft (600m) at assumed horizontal speed 3mph
							// - Swiss Alpine Club: Uses conservative 1 hour per 400m at 4km/h
							//distOnRoadToPass += heightDiff * 6.0f;
							distOnRoadToPass += heightDiff * scarfSeconds;
						}
						prevHeight = height;
					}
				}
			}

			// last point turn time can be added
			// if(i + 1 < result.size()) { distOnRoadToPass += ctx.getRouter().calculateTurnTime(); }
			rr.setDistance((float) distance);
			rr.setSegmentTime((float) distOnRoadToPass);
			if (distOnRoadToPass != 0) {
				rr.setSegmentSpeed((float) (distance / distOnRoadToPass));  //effective segment speed incl. obstacle and height effects
			} else {
				rr.setSegmentSpeed((float) speed);
			}
		}
	}

	public static void recalculateTimeDistance(List<RouteSegmentResult> result) {
		for (int i = 0; i < result.size(); i++) {
			RouteSegmentResult rr = result.get(i);
			RouteDataObject road = rr.getObject();
			double distOnRoadToPass = 0;
			double speed = rr.getSegmentSpeed();
			if (speed == 0) {
				continue;
			}
			boolean plus = rr.getStartPointIndex() < rr.getEndPointIndex();
			int next;
			double distance = 0;
			for (int j = rr.getStartPointIndex(); j != rr.getEndPointIndex(); j = next) {
				next = plus ? j + 1 : j - 1;
				double d = measuredDist(road.getPoint31XTile(j), road.getPoint31YTile(j), road.getPoint31XTile(next),
						road.getPoint31YTile(next));
				distance += d;
				distOnRoadToPass += d / speed;  //this is time in seconds
			}
			rr.setSegmentTime((float) distOnRoadToPass);
			rr.setSegmentSpeed((float) speed);
			rr.setDistance((float) distance);
		}
	}

	private void splitRoadsAndAttachRoadSegments(RoutingContext ctx, List<RouteSegmentResult> result, boolean recalculation) throws IOException {
		for (int i = 0; i < result.size(); i++) {
			if (ctx.checkIfMemoryLimitCritical(ctx.config.memoryLimitation)) {
				ctx.unloadUnusedTiles(ctx.config.memoryLimitation);
			}
			RouteSegmentResult rr = result.get(i);
			boolean plus = rr.getStartPointIndex() < rr.getEndPointIndex();
			int next;
			boolean unmatched = UNMATCHED_HIGHWAY_TYPE.equals(rr.getObject().getHighway());
			for (int j = rr.getStartPointIndex(); j != rr.getEndPointIndex(); j = next) {
				next = plus ? j + 1 : j - 1;
				if (j == rr.getStartPointIndex()) {
					attachRoadSegments(ctx, result, i, j, plus, recalculation);
				}
				if (next != rr.getEndPointIndex()) {
					attachRoadSegments(ctx, result, i, next, plus, recalculation);
				}
				List<RouteSegmentResult> attachedRoutes = rr.getAttachedRoutes(next);
				boolean tryToSplit = next != rr.getEndPointIndex() && !rr.getObject().roundabout() && attachedRoutes != null;
				if (rr.getDistance(next, plus) == 0) {
					// same point will be processed next step
					tryToSplit = false;
				}
				if (tryToSplit) {
					float distBearing = unmatched ? RouteSegmentResult.DIST_BEARING_DETECT_UNMATCHED : RouteSegmentResult.DIST_BEARING_DETECT;
					// avoid small zigzags
					float before = rr.getBearingEnd(next, distBearing);
					float after = rr.getBearingBegin(next, distBearing);
					if (rr.getDistance(next, plus) < distBearing) {
						after = before;
					} else if (rr.getDistance(next, !plus) < distBearing) {
						before = after;
					}
					double contAngle = Math.abs(MapUtils.degreesDiff(before, after));
					boolean straight = contAngle < TURN_DEGREE_MIN;
					boolean isSplit = false;
					
					if (unmatched && Math.abs(contAngle) >= UNMATCHED_TURN_DEGREE_MINIMUM) {
						isSplit = true;
					}
					// split if needed
					for (RouteSegmentResult rs : attachedRoutes) {
						double diff = MapUtils.degreesDiff(before, rs.getBearingBegin());
						if (Math.abs(diff) <= TURN_DEGREE_MIN) {
							isSplit = true;
						} else if (!straight && Math.abs(diff) < SPLIT_TURN_DEGREE_NOT_STRAIGHT) {
							isSplit = true;
						}
					}
					if (isSplit) {
						int endPointIndex = rr.getEndPointIndex();
						RouteSegmentResult split = new RouteSegmentResult(rr.getObject(), next, endPointIndex);
						split.copyPreattachedRoutes(rr, Math.abs(next - rr.getStartPointIndex()));
						rr.setEndPointIndex(next);
						result.add(i + 1, split);
						i++;
						// switch current segment to the splitted
						rr = split;
					}
				}
			}
		}
	}

	private void checkAndInitRouteRegion(RoutingContext ctx, RouteDataObject road) throws IOException {
		BinaryMapIndexReader reader = ctx.reverseMap.get(road.region);
		if (reader != null) {
			reader.initRouteRegion(road.region);
		}
	}

	private void validateAllPointsConnected(List<RouteSegmentResult> result) {
		for (int i = 1; i < result.size(); i++) {
			RouteSegmentResult rr = result.get(i);
			RouteSegmentResult pr = result.get(i - 1);
			double d = MapUtils.getDistance(pr.getPoint(pr.getEndPointIndex()), rr.getPoint(rr.getStartPointIndex()));
			if (d > 0) {
				System.err.println("Points are not connected : " + pr.getObject() + "(" + pr.getEndPointIndex() + ") -> " + rr.getObject()
						+ "(" + rr.getStartPointIndex() + ") " + d + " meters");
			}
		}
	}

	private List<RouteSegmentResult> convertFinalSegmentToResults(RoutingContext ctx, FinalRouteSegment finalSegment) {
		List<RouteSegmentResult> result = new ArrayList<RouteSegmentResult>();
		if (finalSegment != null) {
			ctx.routingTime += finalSegment.distanceFromStart;
			// println("Routing calculated time distance " + finalSegment.distanceFromStart);
			// Get results from opposite direction roads
			RouteSegment segment = finalSegment.reverseWaySearch ? finalSegment.parentRoute : finalSegment.opposite;
			while (segment != null) {
				RouteSegmentResult res = new RouteSegmentResult(segment.road, segment.getSegmentEnd(), segment.getSegmentStart());
				float parentRoutingTime = segment.getParentRoute() != null ? segment.getParentRoute().distanceFromStart : 0;
				res.setRoutingTime(segment.distanceFromStart - parentRoutingTime);
				segment = segment.getParentRoute();
				addRouteSegmentToResult(ctx, result, res, false);
				
			}
			// reverse it just to attach good direction roads
			Collections.reverse(result);
			segment = finalSegment.reverseWaySearch ? finalSegment.opposite : finalSegment.parentRoute;
			while (segment != null) {
				RouteSegmentResult res = new RouteSegmentResult(segment.road, segment.getSegmentStart(), segment.getSegmentEnd());
				float parentRoutingTime = segment.getParentRoute() != null ? segment.getParentRoute().distanceFromStart : 0;
				res.setRoutingTime(segment.distanceFromStart - parentRoutingTime);
				segment = segment.getParentRoute();
				// happens in smart recalculation
				addRouteSegmentToResult(ctx, result, res, true);
			}
			Collections.reverse(result);
			checkTotalRoutingTime(result, finalSegment.distanceFromStart);
		}
		return result;
	}

	protected void checkTotalRoutingTime(List<RouteSegmentResult> result, float cmp) {
		float totalRoutingTime = 0;
		for (RouteSegmentResult r : result) {
			totalRoutingTime += r.getRoutingTime();
		}
		if (Math.abs(totalRoutingTime - cmp) > 1) {
			println("Total sum routing time ! " + totalRoutingTime + " == " + cmp);
		}
	}
	
	private void addRouteSegmentToResult(RoutingContext ctx, List<RouteSegmentResult> result, RouteSegmentResult res, boolean reverse) {
		if (res.getStartPointIndex() != res.getEndPointIndex()) {
			if (result.size() > 0) {
				RouteSegmentResult last = result.get(result.size() - 1);
				if (last.getObject().id == res.getObject().id && ctx.calculationMode != RouteCalculationMode.BASE) {
					if (combineTwoSegmentResult(res, last, reverse)) {
						return;
					}
				}
			}
			result.add(res);
		}
	}
	
	private boolean combineTwoSegmentResult(RouteSegmentResult toAdd, RouteSegmentResult previous, 
			boolean reverse) {
		boolean ld = previous.getEndPointIndex() > previous.getStartPointIndex();
		boolean rd = toAdd.getEndPointIndex() > toAdd.getStartPointIndex();
		if (rd == ld) {
			if (toAdd.getStartPointIndex() == previous.getEndPointIndex() && !reverse) {
				previous.setEndPointIndex(toAdd.getEndPointIndex());
				previous.setRoutingTime(previous.getRoutingTime() + toAdd.getRoutingTime());
				return true;
			} else if (toAdd.getEndPointIndex() == previous.getStartPointIndex() && reverse) {
				previous.setStartPointIndex(toAdd.getStartPointIndex());
				previous.setRoutingTime(previous.getRoutingTime() + toAdd.getRoutingTime());
				return true;
			}
		}
		return false;
	}
	
	public static void printResults(RoutingContext ctx, LatLon start, LatLon end, List<RouteSegmentResult> result) {
		Map<String, Object> info =  new LinkedHashMap<String, Object>();
		Map<String, Object> route =  new LinkedHashMap<String, Object>();
		info.put("route", route);
		
		route.put("routing_time", String.format("%.1f", ctx.routingTime));
		route.put("vehicle", ctx.config.routerName);
		route.put("base", ctx.calculationMode == RouteCalculationMode.BASE);
		route.put("start_lat", String.format("%.5f", start.getLatitude()));
		route.put("start_lon", String.format("%.5f", start.getLongitude()));
		route.put("target_lat", String.format("%.5f", end.getLatitude()));
		route.put("target_lon", String.format("%.5f", end.getLongitude()));
		if (result != null) {
			float completeTime = 0;
			float completeDistance = 0;
			for (RouteSegmentResult r : result) {
				completeTime += r.getSegmentTime();
				completeDistance += r.getDistance();
			}
			route.put("complete_distance", String.format("%.1f", completeDistance));
			route.put("complete_time", String.format("%.1f", completeTime));
			
		}
		route.put("native", ctx.nativeLib != null);
		
		if (ctx.calculationProgress != null && ctx.calculationProgress.timeToCalculate > 0) {
			info.putAll(ctx.calculationProgress.getInfo(ctx.calculationProgressFirstPhase));
		}
		
		String alerts = String.format("Alerts during routing: %d fastRoads, %d slowSegmentsEearlier",
				ctx.alertFasterRoadToVisitedSegments, ctx.alertSlowerSegmentedWasVisitedEarlier);
		if (ctx.alertFasterRoadToVisitedSegments + ctx.alertSlowerSegmentedWasVisitedEarlier == 0) {
			alerts = "No alerts";
		}
		println("ROUTE. " + alerts);
		List<String> routeInfo = new ArrayList<String>();
		StringBuilder extraInfo = buildRouteMessagesFromInfo(info, routeInfo);
		if (PRINT_TO_CONSOLE_ROUTE_INFORMATION_TO_TEST && result != null) {
			println(String.format("<test %s>",extraInfo.toString()));
			printRouteInfoSegments(result);
			println("</test>");
			// duplicate base info
			if (ctx.calculationProgressFirstPhase != null) {
				println("<<<1st Phase>>>>");
				List<String> baseRouteInfo = new ArrayList<String>();
				buildRouteMessagesFromInfo(ctx.calculationProgressFirstPhase.getInfo(null), baseRouteInfo);
				for (String msg : baseRouteInfo) {
					println(msg);
				}
				println("<<<2nd Phase>>>>");
			}
		}
		for (String msg : routeInfo) {
			println(msg);
		}
//		calculateStatistics(result);
	}

	private static StringBuilder buildRouteMessagesFromInfo(Map<String, Object> info, List<String> routeMessages) {
		StringBuilder extraInfo = new StringBuilder(); 
		for (String key : info.keySet()) {
			// // GeneralRouter.TIMER = 0;
			if (info.get(key) instanceof Map) {
				@SuppressWarnings("unchecked")
				Map<String, Object> mp = (Map<String, Object>) info.get(key);
				StringBuilder msg = new StringBuilder("Route <" + key + ">");
				int i = 0;
				for (String mkey : mp.keySet()) {
					msg.append((i++ == 0) ? ": " : ", ");
					Object obj = mp.get(mkey);
					String valueString = obj.toString();
					if (obj instanceof Double || obj instanceof Float) {
						valueString = String.format("%.1f", ((Number) obj).doubleValue());
					}
					msg.append(mkey).append("=").append(valueString);
					extraInfo.append(" ").append(key + "_" + mkey).append("=\"").append(valueString).append("\"");
				}
				if (routeMessages != null) {
					routeMessages.add(msg.toString());
				}
			}
		}
		return extraInfo;
	}

	private static void printRouteInfoSegments(List<RouteSegmentResult> result) {
		org.xmlpull.v1.XmlSerializer serializer = null;
		if (PRINT_TO_GPX_FILE != null) {
			serializer = PlatformUtil.newSerializer();
			try {
				serializer.setOutput(new FileWriter(PRINT_TO_GPX_FILE));
				serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
				// indentation as 3 spaces
				// serializer.setProperty("http://xmlpull.org/v1/doc/properties.html#serializer-indentation", " ");
				// // also set the line separator
				// serializer.setProperty("http://xmlpull.org/v1/doc/properties.html#serializer-line-separator",
				// "\n");
				serializer.startDocument("UTF-8", true);
				serializer.startTag("", "gpx");
				serializer.attribute("", "version", "1.1");
				serializer.attribute("", "xmlns", "http://www.topografix.com/GPX/1/1");
				serializer.attribute("", "xmlns:xsi", "http://www.w3.org/2001/XMLSchema-instance");
				serializer.attribute("", "xmlns:schemaLocation",
						"http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd");
				serializer.startTag("", "trk");
				serializer.startTag("", "trkseg");
			} catch (IOException e) {
				e.printStackTrace();
				serializer = null;
			}
		}
				
		double lastHeight = -180;		
		for (RouteSegmentResult res : result) {
			String name = res.getObject().getName();
			String ref = res.getObject().getRef("", false, res.isForwardDirection());
			if (name == null) {
				name = "";
			}
			if (ref != null) {
				name += " (" + ref + ") ";
			}
			StringBuilder additional = new StringBuilder();
			additional.append("time = \"").append(((int)res.getSegmentTime()*100)/100.0f).append("\" ");
			if (res.getRoutingTime() > 0) {
//					additional.append("rspeed = \"")
//							.append((int) Math.round(res.getDistance() / res.getRoutingTime() * 3.6)).append("\" ");
				additional.append("rtime = \"")
					.append(((int)res.getRoutingTime()*100)/100.0f).append("\" ");
			}
			
//				additional.append("rtime = \"").append(res.getRoutingTime()).append("\" ");
			additional.append("name = \"").append(name).append("\" ");
//				float ms = res.getSegmentSpeed();
			float ms = res.getObject().getMaximumSpeed(res.isForwardDirection());
			if(ms > 0) {
				additional.append("maxspeed = \"").append((int) Math.round(ms * 3.6f)).append("\" ");
			}
			additional.append("distance = \"").append(((int)res.getDistance()*100)/100.0f).append("\" ");
			additional.append(res.getObject().getHighway()).append(" ");
			if (res.getTurnType() != null) {
				additional.append("turn = \"").append(res.getTurnType()).append("\" ");
				additional.append("turn_angle = \"").append(res.getTurnType().getTurnAngle()).append("\" ");
				if (res.getTurnType().getLanes() != null) {
					additional.append("lanes = \"").append(Arrays.toString(res.getTurnType().getLanes())).append("\" ");
				}
			}
			additional.append("start_bearing = \"").append(res.getBearingBegin()).append("\" ");
			additional.append("end_bearing = \"").append(res.getBearingEnd()).append("\" ");
			additional.append("height = \"").append(Arrays.toString(res.getHeightValues())).append("\" ");
			additional.append("description = \"").append(res.getDescription()).append("\" ");
			println(MessageFormat.format("\t<segment id=\"{0}\" oid=\"{1}\" start=\"{2}\" end=\"{3}\" {4}/>",
					(res.getObject().getId() >> (SHIFT_ID )) + "", res.getObject().getId() + "", 
					res.getStartPointIndex() + "", res.getEndPointIndex() + "", additional.toString()));
			int inc = res.getStartPointIndex() < res.getEndPointIndex() ? 1 : -1;
			int indexnext = res.getStartPointIndex();
			LatLon prev = null;
			for (int index = res.getStartPointIndex() ; index != res.getEndPointIndex(); ) {
				index = indexnext;
				indexnext += inc; 
				if (serializer != null) {
					try {
						LatLon l = res.getPoint(index);
						serializer.startTag("","trkpt");
						serializer.attribute("", "lat",  l.getLatitude() + "");
						serializer.attribute("", "lon",  l.getLongitude() + "");
						float[] vls = res.getObject().heightDistanceArray;
						double dist = prev == null ? 0 : MapUtils.getDistance(prev, l);
						if(index * 2 + 1 < vls.length) {
							double h = vls[2*index + 1];
							serializer.startTag("","ele");
							serializer.text(h +"");
							serializer.endTag("","ele");
							if(lastHeight != -180 && dist > 0) {
								serializer.startTag("","cmt");
								serializer.text((float) ((h -lastHeight)/ dist*100) + "% " +
								" degree " + (float) Math.atan(((h -lastHeight)/ dist)) / Math.PI * 180 +  
								" asc " + (float) (h -lastHeight) + " dist "
										+ (float) dist);
								serializer.endTag("","cmt");
								serializer.startTag("","slope");
								serializer.text((h -lastHeight)/ dist*100 + "");
								serializer.endTag("","slope");
							}
							serializer.startTag("","desc");
							serializer.text((res.getObject().getId() >> (SHIFT_ID )) + " " + index);
							serializer.endTag("","desc");
							lastHeight = h;
						} else if(lastHeight != -180){
//								serializer.startTag("","ele");
//								serializer.text(lastHeight +"");
//								serializer.endTag("","ele");
						}
						serializer.endTag("", "trkpt");
						prev = l;
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
			printAdditionalPointInfo(res);
		}
		if (serializer != null) {
			try {
				serializer.endTag("", "trkseg");
				serializer.endTag("", "trk");
				serializer.endTag("", "gpx");
				serializer.endDocument();
				serializer.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	protected void calculateStatistics(List<RouteSegmentResult> result) {
		InputStream is = RenderingRulesStorage.class.getResourceAsStream("default.render.xml");
		final Map<String, String> renderingConstants = new LinkedHashMap<String, String>();
		try {
			InputStream pis = RenderingRulesStorage.class.getResourceAsStream("default.render.xml");
			try {
				XmlPullParser parser = PlatformUtil.newXMLPullParser();
				parser.setInput(pis, "UTF-8");
				int tok;
				while ((tok = parser.next()) != XmlPullParser.END_DOCUMENT) {
					if (tok == XmlPullParser.START_TAG) {
						String tagName = parser.getName();
						if (tagName.equals("renderingConstant")) {
							if (!renderingConstants.containsKey(parser.getAttributeValue("", "name"))) {
								renderingConstants.put(parser.getAttributeValue("", "name"), 
										parser.getAttributeValue("", "value"));
							}
						}
					}
				}
			} finally {
				pis.close();
			}
			RenderingRulesStorage rrs = new RenderingRulesStorage("default", renderingConstants);
			rrs.parseRulesFromXmlInputStream(is, new RenderingRulesStorageResolver() {
				
				@Override
				public RenderingRulesStorage resolve(String name, RenderingRulesStorageResolver ref)
						throws XmlPullParserException, IOException {
					throw new UnsupportedOperationException();
				}
			});
			RenderingRuleSearchRequest req = new RenderingRuleSearchRequest(rrs);
			List<RouteStatistics> rsr = RouteStatisticsHelper.calculateRouteStatistic(result, null, rrs, null, req);
			for(RouteStatistics r : rsr) {
				System.out.println(r);
			}
		} catch (Exception e) {
			throw new IllegalStateException(e.getMessage(), e);
		}
		
	}

	private static void printAdditionalPointInfo(RouteSegmentResult res) {
		boolean plus = res.getStartPointIndex() < res.getEndPointIndex();
		for(int k = res.getStartPointIndex(); k != res.getEndPointIndex(); ) {
			int[] tp = res.getObject().getPointTypes(k);
			String[] pointNames = res.getObject().getPointNames(k);
			int[] pointNameTypes = res.getObject().getPointNameTypes(k);
			if (tp != null || pointNameTypes != null) {
				StringBuilder bld = new StringBuilder();
				bld.append("<point " + (k));
				if (tp != null) {
					for (int t = 0; t < tp.length; t++) {
						RouteTypeRule rr = res.getObject().region.quickGetEncodingRule(tp[t]);
						bld.append(" " + rr.getTag() + "=\"" + rr.getValue() + "\"");
					}
				}
				if (pointNameTypes != null) {
					for (int t = 0; t < pointNameTypes.length; t++) {
						RouteTypeRule rr = res.getObject().region.quickGetEncodingRule(pointNameTypes[t]);
						bld.append(" " + rr.getTag() + "=\"" + pointNames[t] + "\"");
					}
				}
				bld.append("/>");
				println("\t"+bld.toString());
			}
			if(plus) {
				k++;
			} else {
				k--;
			}
		}
	}


	public void addTurnInfoDescriptions(List<RouteSegmentResult> result) {
		int prevSegment = -1;
		float dist = 0;
		for (int i = 0; i <= result.size(); i++) {
			if (i == result.size() || result.get(i).getTurnType() != null) {
				if (prevSegment >= 0) {
					String turn = result.get(prevSegment).getTurnType().toString();
					result.get(prevSegment).setDescription(
							turn + MessageFormat.format(" and go {0,number,#.##} meters", dist));
					if (result.get(prevSegment).getTurnType().isSkipToSpeak()) {
						result.get(prevSegment).setDescription("[MUTE] " + result.get(prevSegment).getDescription());
					}
				}
				prevSegment = i;
				dist = 0;
			}
			if (i < result.size()) {
				dist += result.get(i).getDistance();
			}
		}
	}

	protected TurnType justifyUTurn(boolean leftside, List<RouteSegmentResult> result, int i, TurnType t) {
		boolean tl = TurnType.isLeftTurnNoUTurn(t.getValue());
		boolean tr = TurnType.isRightTurnNoUTurn(t.getValue());
		if(tl || tr) {
			TurnType tnext = result.get(i + 1).getTurnType();
			if (tnext != null && result.get(i).getDistance() < 50) { //
				boolean ut = true;
				if (i > 0) {
					double uTurn = MapUtils.degreesDiff(result.get(i - 1).getBearingEnd(), 
							result.get(i + 1).getBearingBegin());
					if (Math.abs(uTurn) < 120) {
						ut = false;
					}
				}
//				String highway = result.get(i).getObject().getHighway();
//				if(highway == null || highway.endsWith("track") || highway.endsWith("services") || highway.endsWith("service")
//						|| highway.endsWith("path")) {
//					ut = false;
//				}
				if (result.get(i - 1).getObject().getOneway() == 0 || result.get(i + 1).getObject().getOneway() == 0) {
					ut = false;
				}
				if (!Algorithms.objectEquals(getStreetName(result, i - 1, false), 
						getStreetName(result, i + 1, true))) {
					ut = false;
				}
				if (ut) {
					tnext.setSkipToSpeak(true);
					if (tl && TurnType.isLeftTurnNoUTurn(tnext.getValue())) {
						TurnType tt = TurnType.valueOf(TurnType.TU, false);
						tt.setLanes(t.getLanes());
						return tt;
					} else if (tr && TurnType.isRightTurnNoUTurn(tnext.getValue())) {
						TurnType tt = TurnType.valueOf(TurnType.TU, true);
						tt.setLanes(t.getLanes());
						return tt;
					}
				}
			}
		}
		return null;
	}

	private String getStreetName(List<RouteSegmentResult> result, int i, boolean dir) {
		String nm = result.get(i).getObject().getName();
		if (Algorithms.isEmpty(nm)) {
			if (!dir) {
				if (i > 0) {
					nm = result.get(i - 1).getObject().getName();
				}
			} else {
				if(i < result.size() - 1) {
					nm = result.get(i + 1).getObject().getName();
				}
			}
		}
		
		return nm;
	}

	private void determineTurnsToMerge(boolean leftside, List<RouteSegmentResult> result) {
		RouteSegmentResult nextSegment = null;
		double dist = 0;
		for (int i = result.size() - 1; i >= 0; i--) {
			RouteSegmentResult currentSegment = result.get(i);
			TurnType currentTurn = currentSegment.getTurnType();
			dist += currentSegment.getDistance();
			if (currentTurn == null || currentTurn.getLanes() == null) {
				// skip
			} else {
				boolean merged = false;
				if (nextSegment != null) {
					String hw = currentSegment.getObject().getHighway();
					double mergeDistance = 200;
					if (hw != null && (hw.startsWith("trunk") || hw.startsWith("motorway"))) {
						mergeDistance = 400;
					}
					if (dist < mergeDistance) {
						mergeTurnLanes(leftside, currentSegment, nextSegment);
						inferCommonActiveLane(currentSegment.getTurnType(), nextSegment.getTurnType());
						merged = true;
					}
				}
				if (!merged) {
					TurnType tt = currentSegment.getTurnType();
					inferActiveTurnLanesFromTurn(tt, tt.getValue());
				}
				nextSegment = currentSegment;
				dist = 0;
			}
		}
	}

	private void inferActiveTurnLanesFromTurn(TurnType tt, int type) {
		boolean found = false;
		if (tt.getValue() == type && tt.getLanes() != null) {
			for (int it = 0; it < tt.getLanes().length; it++) {
				int turn = tt.getLanes()[it];
				if (TurnType.getPrimaryTurn(turn) == type ||
						TurnType.getSecondaryTurn(turn) == type ||
						TurnType.getTertiaryTurn(turn) == type) {
					found = true;
					break;
				}
			}
		}
		if(found) {
			for (int it = 0; it < tt.getLanes().length; it++) {
				int turn = tt.getLanes()[it];
				if (TurnType.getPrimaryTurn(turn) != type) {
					if(TurnType.getSecondaryTurn(turn) == type) {
						int st = TurnType.getSecondaryTurn(turn);
						TurnType.setSecondaryTurn(tt.getLanes(), it, TurnType.getPrimaryTurn(turn));
						TurnType.setPrimaryTurn(tt.getLanes(), it, st);
					} else if(TurnType.getTertiaryTurn(turn) == type) {
						int st = TurnType.getTertiaryTurn(turn);
						TurnType.setTertiaryTurn(tt.getLanes(), it, TurnType.getPrimaryTurn(turn));
						TurnType.setPrimaryTurn(tt.getLanes(), it, st);
					} else {
						tt.getLanes()[it] = turn & (~1);
					}
				}
			}
		}
	}
	
	private class MergeTurnLaneTurn {
		TurnType turn;
		int[] originalLanes;
		int[] disabledLanes;
		int activeStartIndex = -1;
		int activeEndIndex = -1;
		int activeLen = 0;
		
		public MergeTurnLaneTurn(RouteSegmentResult segment) {
			this.turn = segment.getTurnType();
			if(turn != null) {
				originalLanes = turn.getLanes();
			}
			if(originalLanes != null) {
				disabledLanes = new int[originalLanes.length];
				for (int i = 0; i < originalLanes.length; i++) {
					int ln = originalLanes[i];
					disabledLanes[i] = ln & ~1;
					if ((ln & 1) > 0) {
						if (activeStartIndex == -1) {
							activeStartIndex = i;
						}
						activeEndIndex = i;
						activeLen++;
					}
				}
			}
		}
		
		public boolean isActiveTurnMostLeft() {
			return activeStartIndex == 0;
		}
		public boolean isActiveTurnMostRight() {
			return activeEndIndex == originalLanes.length - 1;
		}
	}
	
	private boolean mergeTurnLanes(boolean leftSide, RouteSegmentResult currentSegment, RouteSegmentResult nextSegment) {
		MergeTurnLaneTurn active = new MergeTurnLaneTurn(currentSegment);
		MergeTurnLaneTurn target = new MergeTurnLaneTurn(nextSegment);
		if (active.activeLen < 2) {
			return false;
		}
		if (target.activeStartIndex == -1) {
			return false;
		}
		boolean changed = false;
		if (target.isActiveTurnMostLeft()) {
			// let only the most left lanes be enabled
			if (target.activeLen < active.activeLen) {
				active.activeEndIndex -= (active.activeLen - target.activeLen);
				changed = true;
			}
		} else if (target.isActiveTurnMostRight()) {
			// next turn is right
			// let only the most right lanes be enabled
			if (target.activeLen < active.activeLen ) {
				active.activeStartIndex += (active.activeLen - target.activeLen);
				changed = true;
			}
		} else {
			// next turn is get through (take out the left and the right turn)
			if (target.activeLen < active.activeLen) {
				if(target.originalLanes.length == active.activeLen) {
					active.activeEndIndex = active.activeStartIndex + target.activeEndIndex;
					active.activeStartIndex = active.activeStartIndex + target.activeStartIndex;
					changed = true;
				} else {
					int straightActiveLen = 0;
					int straightActiveBegin = -1;
					for(int i = active.activeStartIndex; i <= active.activeEndIndex; i++) {
						if(TurnType.hasAnyTurnLane(active.originalLanes[i], TurnType.C)) {
							straightActiveLen++;
							if(straightActiveBegin == -1) {
								straightActiveBegin = i;
							}
						}
					}
					if(straightActiveBegin != -1 && straightActiveLen <= target.activeLen) {
						active.activeStartIndex = straightActiveBegin;
						active.activeEndIndex = straightActiveBegin + straightActiveLen - 1;
						changed = true;
					} else {
						// cause the next-turn goes forward exclude left most and right most lane
						if (active.activeStartIndex == 0) {
							active.activeStartIndex++;
							active.activeLen--;
						}
						if (active.activeEndIndex == active.originalLanes.length - 1) {
							active.activeEndIndex--;
							active.activeLen--;
						}
						float ratio = (active.activeLen - target.activeLen) / 2f;
						if (ratio > 0) {
							active.activeEndIndex = (int) Math.ceil(active.activeEndIndex - ratio);
							active.activeStartIndex = (int) Math.floor(active.activeStartIndex + ratio);
						}
						changed = true;
					}
				}
			}
		}
		if (!changed) {
			return false;
		}

		// set the allowed lane bit
		for (int i = 0; i < active.disabledLanes.length; i++) {
			if (i >= active.activeStartIndex && i <= active.activeEndIndex && 
					active.originalLanes[i] % 2 == 1) {
				active.disabledLanes[i] |= 1;
			}
		}
		TurnType currentTurn = currentSegment.getTurnType();
		currentTurn.setLanes(active.disabledLanes);
		return true;
	}
	
	private void inferCommonActiveLane(TurnType currentTurn, TurnType nextTurn) {
		int[] lanes = currentTurn.getLanes();
		TIntHashSet turnSet = new TIntHashSet();
		for (int i = 0; i < lanes.length; i++) {
			if (lanes[i] % 2 == 1) {
				int singleTurn = TurnType.getPrimaryTurn(lanes[i]);
				turnSet.add(singleTurn);
				if (TurnType.getSecondaryTurn(lanes[i]) != 0) {
					turnSet.add(TurnType.getSecondaryTurn(lanes[i]));
				}
				if (TurnType.getTertiaryTurn(lanes[i]) != 0) {
					turnSet.add(TurnType.getTertiaryTurn(lanes[i]));
				}
			}
		}
		int singleTurn = 0;
		if (turnSet.size() == 1) {
			singleTurn = turnSet.iterator().next();
		} else if((currentTurn.goAhead() || currentTurn.keepLeft() || currentTurn.keepRight())  
				&& turnSet.contains(nextTurn.getValue())) {
			if (currentTurn.isPossibleLeftTurn() && TurnType.isLeftTurn(nextTurn.getValue())) {
				singleTurn = nextTurn.getValue();
			} else if (currentTurn.isPossibleLeftTurn() && TurnType.isLeftTurn(nextTurn.getActiveCommonLaneTurn())) {
				singleTurn = nextTurn.getActiveCommonLaneTurn();
			} else if (currentTurn.isPossibleRightTurn() && TurnType.isRightTurn(nextTurn.getValue())) {
				singleTurn = nextTurn.getValue();
			} else if (currentTurn.isPossibleRightTurn() && TurnType.isRightTurn(nextTurn.getActiveCommonLaneTurn())) {
				singleTurn = nextTurn.getActiveCommonLaneTurn();
			}
		}
		if (singleTurn == 0) {
			singleTurn = currentTurn.getValue();
			if (singleTurn == TurnType.KL || singleTurn == TurnType.KR) {
				return;
			}
		}
		for (int i = 0; i < lanes.length; i++) {
			if (lanes[i] % 2 == 1 && TurnType.getPrimaryTurn(lanes[i]) != singleTurn) {
				if (TurnType.getSecondaryTurn(lanes[i]) == singleTurn) {
					TurnType.setSecondaryTurn(lanes, i, TurnType.getPrimaryTurn(lanes[i]));
					TurnType.setPrimaryTurn(lanes, i, singleTurn);
				} else if (TurnType.getTertiaryTurn(lanes[i]) == singleTurn) {
					TurnType.setTertiaryTurn(lanes, i, TurnType.getPrimaryTurn(lanes[i]));
					TurnType.setPrimaryTurn(lanes, i, singleTurn);
				} else {
					// disable lane
					lanes[i] = lanes[i] - 1;
				}
			}
		}
		
	}

	private static final int MAX_SPEAK_PRIORITY = 5;
	private int highwaySpeakPriority(String highway) {
		if(highway == null || highway.endsWith("track") || highway.endsWith("services") || highway.endsWith("service")
				|| highway.endsWith("path")) {
			return MAX_SPEAK_PRIORITY;
		}
		if (highway.endsWith("_link")  || highway.endsWith("unclassified") || highway.endsWith("road") 
				|| highway.endsWith("living_street") || highway.endsWith("residential") || highway.endsWith("tertiary") )  {
			return 1;
		}
		return 0;
	}


	private TurnType getTurnInfo(List<RouteSegmentResult> result, int i, boolean leftSide) {
		if (i == 0) {
			return TurnType.valueOf(TurnType.C, false);
		}
		RouteSegmentResult prev = result.get(i - 1) ;
		if(prev.getObject().roundabout()) {
			// already analyzed!
			return null;
		}
		RouteSegmentResult rr = result.get(i);
		if (rr.getObject().roundabout()) {
			return processRoundaboutTurn(result, i, leftSide, prev, rr);
		}
		TurnType t = null;
		if (prev != null) {
			// add description about turn
			// avoid small zigzags is covered at (search for "zigzags")
			float bearingDist = RouteSegmentResult.DIST_BEARING_DETECT;
			// could be || noAttachedRoads, boolean noAttachedRoads = rr.getAttachedRoutes(rr.getStartPointIndex()).size() == 0;
			if (UNMATCHED_HIGHWAY_TYPE.equals(rr.getObject().getHighway())) {
				bearingDist = RouteSegmentResult.DIST_BEARING_DETECT_UNMATCHED;
			}
			double mpi = MapUtils.degreesDiff(prev.getBearingEnd(prev.getEndPointIndex(), Math.min(prev.getDistance(), bearingDist)), 
					rr.getBearingBegin(rr.getStartPointIndex(), Math.min(rr.getDistance(), bearingDist)));
			if (mpi >= TURN_DEGREE_MIN) {
				if (mpi < TURN_DEGREE_MIN) {
					// Slight turn detection here causes many false positives where drivers would expect a "normal" TL. Best use limit-angle=TURN_DEGREE_MIN, this reduces TSL to the turn-lanes cases.
					t = TurnType.valueOf(TurnType.TSLL, leftSide);
				} else if (mpi < 120) {
					t = TurnType.valueOf(TurnType.TL, leftSide);
				} else if (mpi < 150 || leftSide) {
					t = TurnType.valueOf(TurnType.TSHL, leftSide);
				} else {
					t = TurnType.valueOf(TurnType.TU, leftSide);
				}
				int[] lanes = getTurnLanesInfo(prev, t.getValue());
				t.setLanes(lanes);
			} else if (mpi < -TURN_DEGREE_MIN) {
				if (mpi > -TURN_DEGREE_MIN) {
					t = TurnType.valueOf(TurnType.TSLR, leftSide);
				} else if (mpi > -120) {
					t = TurnType.valueOf(TurnType.TR, leftSide);
				} else if (mpi > -150 || !leftSide) {
					t = TurnType.valueOf(TurnType.TSHR, leftSide);
				} else {
					t = TurnType.valueOf(TurnType.TRU, leftSide);
				}
				int[] lanes = getTurnLanesInfo(prev, t.getValue());
				t.setLanes(lanes);
			} else {
				t = attachKeepLeftInfoAndLanes(leftSide, prev, rr);
			}
			if (t != null) {
				t.setTurnAngle((float) - mpi);
			}
		}
		return t;
	}

	private int[] getTurnLanesInfo(RouteSegmentResult prevSegm, int mainTurnType) {
		String turnLanes = getTurnLanesString(prevSegm);
		int[] lanesArray;
		if (turnLanes == null) {
			if(prevSegm.getTurnType() != null && prevSegm.getTurnType().getLanes() != null
					&& prevSegm.getDistance() < 100) {
				int[] lns = prevSegm.getTurnType().getLanes();
				TIntArrayList lst = new TIntArrayList();
				for(int i = 0; i < lns.length; i++) {
					if(lns[i] % 2 == 1) {
						lst.add((lns[i] >> 1) << 1);
					}
				}
				if(lst.isEmpty()) {
					return null;
				}
				lanesArray = lst.toArray();
			} else {
				return null;
			}
		} else {
			lanesArray = calculateRawTurnLanes(turnLanes, mainTurnType);
		}
		// Manually set the allowed lanes.
		boolean isSet = setAllowedLanes(mainTurnType, lanesArray);
		if(!isSet && lanesArray.length > 0) {
			// In some cases (at least in the US), the rightmost lane might not have a right turn indicated as per turn:lanes,
			// but is allowed and being used here. This section adds in that indicator.  The same applies for where leftSide is true.
			boolean leftTurn = TurnType.isLeftTurn(mainTurnType);
			int ind = leftTurn? 0 : lanesArray.length - 1;
			int primaryTurn = TurnType.getPrimaryTurn(lanesArray[ind]);
			final int st = TurnType.getSecondaryTurn(lanesArray[ind]);
			if (leftTurn) {
				if (!TurnType.isLeftTurn(primaryTurn)) {
					// This was just to make sure that there's no bad data.
					TurnType.setPrimaryTurnAndReset(lanesArray, ind, TurnType.TL);
					TurnType.setSecondaryTurn(lanesArray, ind, primaryTurn);
					TurnType.setTertiaryTurn(lanesArray, ind, st);
					primaryTurn = TurnType.TL;
					lanesArray[ind] |= 1;
				}
			} else {
				if (!TurnType.isRightTurn(primaryTurn)) {
					// This was just to make sure that there's no bad data.
					TurnType.setPrimaryTurnAndReset(lanesArray, ind, TurnType.TR);
					TurnType.setSecondaryTurn(lanesArray, ind, primaryTurn);
					TurnType.setTertiaryTurn(lanesArray, ind, st);
					primaryTurn = TurnType.TR;
					lanesArray[ind] |= 1;
				}
			}
			setAllowedLanes(primaryTurn, lanesArray);
		}
		return lanesArray;
	}

	protected boolean setAllowedLanes(int mainTurnType, int[] lanesArray) {
		boolean turnSet = false;
		for (int i = 0; i < lanesArray.length; i++) {
			if (TurnType.getPrimaryTurn(lanesArray[i]) == mainTurnType) {
				lanesArray[i] |= 1;
				turnSet = true;
			}
		}
		return turnSet;
	}

	private TurnType processRoundaboutTurn(List<RouteSegmentResult> result, int i, boolean leftSide, RouteSegmentResult prev,
			RouteSegmentResult rr) {
		int exit = 1;
		RouteSegmentResult last = rr;
		RouteSegmentResult firstRoundabout = rr;
		RouteSegmentResult lastRoundabout = rr;
		
		for (int j = i; j < result.size(); j++) {
			RouteSegmentResult rnext = result.get(j);
			last = rnext;
			if (rnext.getObject().roundabout()) {
				lastRoundabout = rnext;
				boolean plus = rnext.getStartPointIndex() < rnext.getEndPointIndex();
				int k = rnext.getStartPointIndex();
				if (j == i) {
					// first exit could be immediately after roundabout enter
//					k = plus ? k + 1 : k - 1;
				}
				while (k != rnext.getEndPointIndex()) {
					int attachedRoads = rnext.getAttachedRoutes(k).size();
					if(attachedRoads > 0) {
						exit++;
					}
					k = plus ? k + 1 : k - 1;
				}
			} else {
				break;
			}
		}
		// combine all roundabouts
		TurnType t = TurnType.getExitTurn(exit, 0, leftSide);
		// usually covers more than expected
		float turnAngleBasedOnOutRoads = (float) MapUtils.degreesDiff(last.getBearingBegin(), prev.getBearingEnd());
		// Angle based on circle method tries 
		// 1. to calculate antinormal to roundabout circle on roundabout entrance and 
		// 2. normal to roundabout circle on roundabout exit
		// 3. calculate angle difference
		// This method doesn't work if you go from S to N touching only 1 point of roundabout, 
		// but it is very important to identify very sharp or very large angle to understand did you pass whole roundabout or small entrance
		float turnAngleBasedOnCircle = (float) -MapUtils.degreesDiff(firstRoundabout.getBearingBegin(), lastRoundabout.getBearingEnd() + 180);
		if (Math.abs(turnAngleBasedOnOutRoads) > 120) {
			// correctly identify if angle is +- 180, so we approach from left or right side
			t.setTurnAngle(turnAngleBasedOnCircle) ;
		} else {
			t.setTurnAngle(turnAngleBasedOnOutRoads) ;
		}
		return t;
	}
	
	private class RoadSplitStructure {
		boolean keepLeft = false;
		boolean keepRight = false;
		boolean speak = false;
		List<int[]> leftLanesInfo = new ArrayList<int[]>();
		int leftLanes = 0;
		List<int[]> rightLanesInfo = new ArrayList<int[]>();
		int rightLanes = 0;
		int roadsOnLeft = 0;
		int addRoadsOnLeft = 0;
		int roadsOnRight = 0;
		int addRoadsOnRight = 0;
	}


	private TurnType attachKeepLeftInfoAndLanes(boolean leftSide, RouteSegmentResult prevSegm, RouteSegmentResult currentSegm) {
		List<RouteSegmentResult> attachedRoutes = currentSegm.getAttachedRoutes(currentSegm.getStartPointIndex());
		if(attachedRoutes == null || attachedRoutes.isEmpty()) {
			return null;
		}
		String turnLanesPrevSegm = getTurnLanesString(prevSegm);
		// keep left/right
		RoadSplitStructure rs = calculateRoadSplitStructure(prevSegm, currentSegm, attachedRoutes, turnLanesPrevSegm);
		if(rs.roadsOnLeft  + rs.roadsOnRight == 0) {
			return null;
		}
		
		// turn lanes exist
		if (turnLanesPrevSegm != null) {
			return createKeepLeftRightTurnBasedOnTurnTypes(rs, prevSegm, currentSegm, turnLanesPrevSegm, leftSide);
		}

		// turn lanes don't exist
		if (rs.keepLeft || rs.keepRight) {
			return createSimpleKeepLeftRightTurn(leftSide, prevSegm, currentSegm, rs);
			
		}
		return null;
	}

	protected TurnType createKeepLeftRightTurnBasedOnTurnTypes(RoadSplitStructure rs, RouteSegmentResult prevSegm,
			RouteSegmentResult currentSegm, String turnLanes, boolean leftSide) {
		// Maybe going straight at a 90-degree intersection
		TurnType t = TurnType.valueOf(TurnType.C, leftSide);
		int[] rawLanes = calculateRawTurnLanes(turnLanes, TurnType.C);
		boolean possiblyLeftTurn = rs.roadsOnLeft == 0;
		boolean possiblyRightTurn = rs.roadsOnRight == 0;
		for (int k = 0; k < rawLanes.length; k++) {
			int turn = TurnType.getPrimaryTurn(rawLanes[k]);
			int sturn = TurnType.getSecondaryTurn(rawLanes[k]);
			int tturn = TurnType.getTertiaryTurn(rawLanes[k]);
			if (turn == TurnType.TU || sturn == TurnType.TU || tturn == TurnType.TU) {
				possiblyLeftTurn = true;
			}
			if (turn == TurnType.TRU || sturn == TurnType.TRU || tturn == TurnType.TRU) {
				possiblyRightTurn = true;
			}
		}
		
		if (rs.keepLeft || rs.keepRight) {
			String[] splitLaneOptions = turnLanes.split("\\|", -1);
			int activeBeginIndex = findActiveIndex(rawLanes, splitLaneOptions, rs.leftLanes, true, 
					rs.leftLanesInfo, rs.roadsOnLeft, rs.addRoadsOnLeft);
			int activeEndIndex = findActiveIndex(rawLanes, splitLaneOptions, rs.rightLanes, false, 
					rs.rightLanesInfo, rs.roadsOnRight, rs.addRoadsOnRight);
			if (activeBeginIndex == -1 || activeEndIndex == -1 || activeBeginIndex > activeEndIndex) {
				// something went wrong
				return createSimpleKeepLeftRightTurn(leftSide, prevSegm, currentSegm, rs);
			}
			for (int k = 0; k < rawLanes.length; k++) {
				if (k >= activeBeginIndex && k <= activeEndIndex) {
					rawLanes[k] |= 1;
				}
			}
			int tp = inferSlightTurnFromActiveLanes(rawLanes, rs.keepLeft, rs.keepRight);
			// Checking to see that there is only one unique turn
			if (tp != 0) {
				// add extra lanes with same turn
				for(int i = 0; i < rawLanes.length; i++) {
					if (TurnType.getSecondaryTurn(rawLanes[i]) == tp) {
						TurnType.setSecondaryToPrimary(rawLanes, i);
						rawLanes[i] |= 1;
					} else if(TurnType.getPrimaryTurn(rawLanes[i]) == tp) {
						rawLanes[i] |= 1;
					}
				}
			}
			if (tp != t.getValue() && tp != 0) {
				t = TurnType.valueOf(tp, leftSide);
			} else {
				//use keepRight and keepLeft turns when attached road doesn't have lanes
				//or prev segment has more then 1 turn to the active lane
				if (rs.keepRight) {
					t = getTurnByCurrentTurns(rs.leftLanesInfo, currentSegm, rawLanes, TurnType.KR, leftSide);
				} else if (rs.keepLeft ) {
					t = getTurnByCurrentTurns(rs.rightLanesInfo, currentSegm, rawLanes, TurnType.KL, leftSide);
				}
			}
		} else {
			// case for go straight and identify correct turn:lane to go straight
			Integer[] possibleTurns = getPossibleTurns(rawLanes, false, false);
			int tp = TurnType.C;
			if (possibleTurns.length == 1) {
				tp = possibleTurns[0];
			} else if (possibleTurns.length == 3) {
				if ((!possiblyLeftTurn || !possiblyRightTurn) && TurnType.isSlightTurn(possibleTurns[1])) {
					tp = possibleTurns[1];
					t = TurnType.valueOf(tp, leftSide);
				}
			}
			for (int k = 0; k < rawLanes.length; k++) {
				int turn = TurnType.getPrimaryTurn(rawLanes[k]);
				int sturn = TurnType.getSecondaryTurn(rawLanes[k]);
				int tturn = TurnType.getTertiaryTurn(rawLanes[k]);

				boolean active = false;

				// some turns go through many segments (to turn right or left)
				// so on one first segment the lane could be available and may be only 1 possible
				// all undesired lanes will be disabled through the 2nd pass
				if ((TurnType.isRightTurn(sturn) && possiblyRightTurn)
						|| (TurnType.isLeftTurn(sturn) && possiblyLeftTurn)) {
					// we can't predict here whether it will be a left turn or straight on,
					// it could be done during 2nd pass
						TurnType.setSecondaryToPrimary(rawLanes, k);
					active = true;
				} else if ((TurnType.isRightTurn(tturn) && possiblyRightTurn)
						|| (TurnType.isLeftTurn(tturn) && possiblyLeftTurn)) {
					TurnType.setTertiaryToPrimary(rawLanes, k);
					active = true;
				} else if ((TurnType.isRightTurn(turn) && possiblyRightTurn)
						|| (TurnType.isLeftTurn(turn) && possiblyLeftTurn)) {
					active = true;
				} else if (TurnType.isSlightTurn(turn) && !possiblyRightTurn && !possiblyLeftTurn) {
					active = true;
				} else if (turn == tp) {
					active = true;
				}
				if (active) {
					rawLanes[k] |= 1;
				}
			}
		}
		t.setSkipToSpeak(!rs.speak);
		t.setLanes(rawLanes);
		t.setPossibleLeftTurn(possiblyLeftTurn);
		t.setPossibleRightTurn(possiblyRightTurn);
		return t;
	}

	private TurnType getTurnByCurrentTurns(List<int[]> otherSideLanesInfo, RouteSegmentResult currentSegm, int[] rawLanes, int keepTurnType, boolean leftSide) {
		TIntHashSet otherSideTurns = new TIntHashSet();
		if (otherSideLanesInfo != null) {
			for (int[] li : otherSideLanesInfo) {
				if (li != null) {
					for (int i : li) {
						TurnType.collectTurnTypes(i, otherSideTurns);
					}
				}
			}
		}
		TIntHashSet currentTurns = new TIntHashSet();
		for (int ln : rawLanes) {
			TurnType.collectTurnTypes(ln, currentTurns);
		}
		// Here we detect single case when turn lane continues on 1 road / single sign and all other lane turns continue on the other side roads  
		if (currentTurns.containsAll(otherSideTurns)) {
			currentTurns.removeAll(otherSideTurns);
			if (currentTurns.size() == 1) {
				return TurnType.valueOf(currentTurns.iterator().next(), leftSide);
			}
		}

		
		return TurnType.valueOf(keepTurnType, leftSide);
	}

	protected int findActiveIndex(int[] rawLanes, String[] splitLaneOptions, int lanes, boolean left, 
			List<int[]> lanesInfo, int roads, int addRoads) {
		int activeStartIndex = -1;
		boolean lookupSlightTurn = addRoads > 0;
		TIntHashSet addedTurns = new TIntHashSet();
		// if we have information increase number of roads per each turn direction
		int diffTurnRoads = roads;
		int increaseTurnRoads = 0;
		for(int[] li : lanesInfo) {
			TIntHashSet set = new TIntHashSet();
			if(li != null) {
				for (int i : li) {
					TurnType.collectTurnTypes(i, set);
				}
			}
			increaseTurnRoads = Math.max(set.size() - 1, 0);
		}
		
		for (int i = 0; i < rawLanes.length; i++) {
			int ind = left ? i : (rawLanes.length - i - 1);
			if (!lookupSlightTurn ||
					TurnType.hasAnySlightTurnLane(rawLanes[ind])) {
				String[] laneTurns = splitLaneOptions[ind].split(";");
				int cnt = 0;
				for(String lTurn : laneTurns) {
					boolean added = addedTurns.add(TurnType.convertType(lTurn));
					if(added) {
						cnt++;
						diffTurnRoads --;
					}
				}
				lanes -= cnt;
				// we already found slight turn others are turn in different direction
				lookupSlightTurn = false;
			}
			if (lanes < 0 || diffTurnRoads + increaseTurnRoads < 0) {
				activeStartIndex = ind;
				break;
			} else if(diffTurnRoads < 0 && activeStartIndex < 0) {
				activeStartIndex = ind;
			}
		}
		return activeStartIndex;
	}

	protected RoadSplitStructure calculateRoadSplitStructure(RouteSegmentResult prevSegm, RouteSegmentResult currentSegm,
			List<RouteSegmentResult> attachedRoutes, String turnLanesPrevSegm) {
		RoadSplitStructure rs = new RoadSplitStructure();
		int speakPriority = Math.max(highwaySpeakPriority(prevSegm.getObject().getHighway()), highwaySpeakPriority(currentSegm.getObject().getHighway()));
		for (RouteSegmentResult attached : attachedRoutes) {
			boolean restricted = false;
			for(int k = 0; k < prevSegm.getObject().getRestrictionLength(); k++) {
				if(prevSegm.getObject().getRestrictionId(k) == attached.getObject().getId() && 
						prevSegm.getObject().getRestrictionType(k) <= MapRenderingTypes.RESTRICTION_NO_STRAIGHT_ON) {
					restricted = true;
					break;
				}
			}
			if(restricted) {
				continue;
			}
			double ex = MapUtils.degreesDiff(attached.getBearingBegin(), currentSegm.getBearingBegin());
			double mpi = Math.abs(MapUtils.degreesDiff(prevSegm.getBearingEnd(), attached.getBearingBegin()));
			int rsSpeakPriority = highwaySpeakPriority(attached.getObject().getHighway());
			int lanes = countLanesMinOne(attached);
			int[] turnLanesAttachedRoad = parseTurnLanes(attached.getObject(), attached.getBearingBegin() * Math.PI / 180);
			boolean smallStraightVariation = mpi < TURN_DEGREE_MIN;
			boolean smallTargetVariation = Math.abs(ex) < TURN_DEGREE_MIN;
			boolean attachedOnTheRight = ex >= 0;
			boolean verySharpTurn = Math.abs(ex) > 150;
			boolean prevSegmHasTU = hasTU(turnLanesPrevSegm, attachedOnTheRight);

			if (!verySharpTurn && !prevSegmHasTU) {
				if (attachedOnTheRight) {
					rs.roadsOnRight++;
				} else {
					rs.roadsOnLeft++;
				}
			}

			if (turnLanesPrevSegm != null || rsSpeakPriority != MAX_SPEAK_PRIORITY || speakPriority == MAX_SPEAK_PRIORITY) {
				if (smallTargetVariation || smallStraightVariation) {
					if (attachedOnTheRight) {
						rs.keepLeft = true;
						rs.rightLanes += lanes;
						if(turnLanesAttachedRoad != null) {
							rs.rightLanesInfo.add(turnLanesAttachedRoad);
						}
					} else {
						rs.keepRight = true;
						rs.leftLanes += lanes;
						if(turnLanesAttachedRoad != null) {
							rs.leftLanesInfo.add(turnLanesAttachedRoad);
						}
					}
					rs.speak = rs.speak || rsSpeakPriority <= speakPriority;
				} else {
					if (attachedOnTheRight) {
						rs.addRoadsOnRight++;
					} else {
						rs.addRoadsOnLeft++;
					}
				}
			}
		}
		return rs;
	}
	
	private boolean hasTU(String turnLanesPrevSegm, boolean attachedOnTheRight) {
		if (turnLanesPrevSegm != null) {
			int[] turns = calculateRawTurnLanes(turnLanesPrevSegm, TurnType.C);
			int lane = attachedOnTheRight ? turns[turns.length - 1] : turns[0];
			List<Integer> turnList = new ArrayList<>();
			turnList.add(TurnType.getPrimaryTurn(lane));
			turnList.add(TurnType.getSecondaryTurn(lane));
			turnList.add(TurnType.getTertiaryTurn(lane));
			if (attachedOnTheRight) {
				Collections.reverse(turnList);
			}
			return foundTUturn(turnList);
		}
		return false;
	}
	
	private boolean foundTUturn(List<Integer> turnList) {
		for (int t : turnList) {
			if (t != 0) {
				return t == TurnType.TU;
			}
		}
		return false;
	}

	protected TurnType createSimpleKeepLeftRightTurn(boolean leftSide, RouteSegmentResult prevSegm,
			RouteSegmentResult currentSegm, RoadSplitStructure rs) {
		double devation = Math.abs(MapUtils.degreesDiff(prevSegm.getBearingEnd(), currentSegm.getBearingBegin()));
		boolean makeSlightTurn = devation > 5 && (!isMotorway(prevSegm) || !isMotorway(currentSegm));
		TurnType t = null;
		int laneType = TurnType.C;
		if (rs.keepLeft && rs.keepRight) {
			t = TurnType.valueOf(TurnType.C, leftSide);
		} else if (rs.keepLeft) {
			t = TurnType.valueOf(makeSlightTurn ? TurnType.TSLL : TurnType.KL, leftSide);
			if (makeSlightTurn) {
				laneType = TurnType.TSLL;
			}
		} else if (rs.keepRight) {
			t = TurnType.valueOf(makeSlightTurn ? TurnType.TSLR : TurnType.KR, leftSide);
			if (makeSlightTurn) {
				laneType = TurnType.TSLR;
			}
		} else {
			return null;
		}
		int current = countLanesMinOne(currentSegm);
		int ls = current + rs.leftLanes + rs.rightLanes;
		int[] lanes = new int[ls];
		for (int it = 0; it < ls; it++) {
			if (it < rs.leftLanes || it >= rs.leftLanes + current) {
				lanes[it] = TurnType.C << 1;
			} else {
				lanes[it] = (laneType << 1) + 1;
			}
		}
		t.setSkipToSpeak(!rs.speak);
		t.setLanes(lanes);
		return t;
	}

	
	protected int countLanesMinOne(RouteSegmentResult attached) {
		final boolean oneway = attached.getObject().getOneway() != 0;
		int lns = attached.getObject().getLanes();
		if(lns == 0) {
			String tls = getTurnLanesString(attached);
			if(tls != null) {
				return Math.max(1, countOccurrences(tls, '|'));
			}
		}
		if (oneway) {
			return Math.max(1, lns);
		}
		try {
			if (attached.isForwardDirection() && attached.getObject().getValue("lanes:forward") != null) {
				return Integer.parseInt(attached.getObject().getValue("lanes:forward"));
			} else if (!attached.isForwardDirection() && attached.getObject().getValue("lanes:backward") != null) {
				return Integer.parseInt(attached.getObject().getValue("lanes:backward"));
			}
		} catch(NumberFormatException e) {
			e.printStackTrace();
		}
		return Math.max(1, (lns + 1) / 2);
	}

	protected static String getTurnLanesString(RouteSegmentResult segment) {
		if (segment.getObject().getOneway() == 0) {
			if (segment.isForwardDirection()) {
				return segment.getObject().getValue("turn:lanes:forward");
			} else {
				return segment.getObject().getValue("turn:lanes:backward");
			}
		} else {
			return segment.getObject().getValue("turn:lanes");
		}
	}

	

	private int countOccurrences(String haystack, char needle) {
	    int count = 0;
		for (int i = 0; i < haystack.length(); i++) {
			if (haystack.charAt(i) == needle) {
				count++;
			}
		}
		return count;
	}

	public static int[] parseTurnLanes(RouteDataObject ro, double dirToNorthEastPi) {
		String turnLanes = null;
		if (ro.getOneway() == 0) {
			// we should get direction to detect forward or backward
			double cmp = ro.directionRoute(0, true);
			if(Math.abs(MapUtils.alignAngleDifference(dirToNorthEastPi -cmp)) < Math.PI / 2) {
				turnLanes = ro.getValue("turn:lanes:forward");
			} else {
				turnLanes = ro.getValue("turn:lanes:backward");
			}
		} else {
			turnLanes = ro.getValue("turn:lanes");
		}
		if(turnLanes == null) {
			return null;
		}
		return calculateRawTurnLanes(turnLanes, 0);
	}
	
	public static int[] parseLanes(RouteDataObject ro, double dirToNorthEastPi) {
		int lns = 0;
		try {
			if (ro.getOneway() == 0) {
				// we should get direction to detect forward or backward
				double cmp = ro.directionRoute(0, true);
				
				if(Math.abs(MapUtils.alignAngleDifference(dirToNorthEastPi -cmp)) < Math.PI / 2) {
					if(ro.getValue("lanes:forward") != null) {
						lns = Integer.parseInt(ro.getValue("lanes:forward"));
					}
				} else {
					if(ro.getValue("lanes:backward") != null) {
					lns = Integer.parseInt(ro.getValue("lanes:backward"));
					}
				}
				if (lns == 0 && ro.getValue("lanes") != null) {
					lns = Integer.parseInt(ro.getValue("lanes")) / 2;
				}
			} else {
				lns = Integer.parseInt(ro.getValue("lanes"));
			}
			if(lns > 0 ) {
				return new int[lns];
			}
		} catch (NumberFormatException e) {
		}
		return null;
	}
	
	public static int[] calculateRawTurnLanes(String turnLanes, int calcTurnType) {
		String[] splitLaneOptions = turnLanes.split("\\|", -1);
		int[] lanes = new int[splitLaneOptions.length];
		for (int i = 0; i < splitLaneOptions.length; i++) {
			String[] laneOptions = splitLaneOptions[i].split(";");
			for (int j = 0; j < laneOptions.length; j++) {
				int turn = TurnType.convertType(laneOptions[j]);
				final int primary = TurnType.getPrimaryTurn(lanes[i]);
				if (primary == 0) {
					TurnType.setPrimaryTurnAndReset(lanes, i, turn);
				} else {
                    if (turn == calcTurnType || 
                    	(TurnType.isRightTurn(calcTurnType) && TurnType.isRightTurn(turn)) || 
                    	(TurnType.isLeftTurn(calcTurnType) && TurnType.isLeftTurn(turn)) 
                    	) {
                    	TurnType.setPrimaryTurnShiftOthers(lanes, i, turn);
                    } else if (TurnType.getSecondaryTurn(lanes[i]) == 0) {
                    	TurnType.setSecondaryTurn(lanes, i, turn);
                    } else if (TurnType.getTertiaryTurn(lanes[i]) == 0) {
						TurnType.setTertiaryTurn(lanes, i, turn);
                    } else {
                    	// ignore
                    }
				}
			}
		}
		return lanes;
	}

	
	private int inferSlightTurnFromActiveLanes(int[] oLanes, boolean mostLeft, boolean mostRight) {
		Integer[] possibleTurns = getPossibleTurns(oLanes, false, false);
		if (possibleTurns.length == 0) {
			// No common turns, so can't determine anything.
			return 0;
		}
		int infer = 0;
		if (possibleTurns.length == 1) {
			infer = possibleTurns[0];
		} else if (possibleTurns.length == 2) {
			// this method could be adapted for 3+ turns 
			if (mostLeft && !mostRight) {
				infer = possibleTurns[0];
			} else if (mostRight && !mostLeft) {
				infer = possibleTurns[possibleTurns.length - 1];
			} else {
				infer = possibleTurns[1];
				// infer = TurnType.C;
			}
		}
		return infer;
	}
	
	private Integer[] getPossibleTurns(int[] oLanes, boolean onlyPrimary, boolean uniqueFromActive) {
		Set<Integer> possibleTurns = new LinkedHashSet<>();
		Set<Integer> upossibleTurns = new LinkedHashSet<>();
		for (int i = 0; i < oLanes.length; i++) {
			// Nothing is in the list to compare to, so add the first elements
			upossibleTurns.clear();
			upossibleTurns.add(TurnType.getPrimaryTurn(oLanes[i]));
			if (!onlyPrimary && TurnType.getSecondaryTurn(oLanes[i]) != 0) {
				upossibleTurns.add(TurnType.getSecondaryTurn(oLanes[i]));
			}
			if (!onlyPrimary && TurnType.getTertiaryTurn(oLanes[i]) != 0) {
				upossibleTurns.add(TurnType.getTertiaryTurn(oLanes[i]));
			}
			if (!uniqueFromActive) {
				possibleTurns.addAll(upossibleTurns);
//				if (!possibleTurns.isEmpty()) {
//					possibleTurns.retainAll(upossibleTurns);
//					if(possibleTurns.isEmpty()) {
//						break;
//					}
//				} else {
//					possibleTurns.addAll(upossibleTurns);
//				}
			} else if ((oLanes[i] & 1) == 1) {
				if (!possibleTurns.isEmpty()) {
					possibleTurns.retainAll(upossibleTurns);
					if(possibleTurns.isEmpty()) {
						break;
					}
				} else {
					possibleTurns.addAll(upossibleTurns);
				}
			}
		}
		// Remove all turns from lanes not selected...because those aren't it
		if (uniqueFromActive) {
			for (int i = 0; i < oLanes.length; i++) {
				if ((oLanes[i] & 1) == 0) {
					possibleTurns.remove((Integer) TurnType.getPrimaryTurn(oLanes[i]));
					if (TurnType.getSecondaryTurn(oLanes[i]) != 0) {
						possibleTurns.remove((Integer) TurnType.getSecondaryTurn(oLanes[i]));
					}
					if (TurnType.getTertiaryTurn(oLanes[i]) != 0) {
						possibleTurns.remove((Integer) TurnType.getTertiaryTurn(oLanes[i]));
					}
				}
			}
		}
		Integer[] array = possibleTurns.toArray(new Integer[0]);
		Arrays.sort(array, new Comparator<Integer>() {

			@Override
			public int compare(Integer o1, Integer o2) {
				return Integer.compare(TurnType.orderFromLeftToRight(o1), TurnType.orderFromLeftToRight(o2));
			}
		});
		return array;
	}

	private boolean isMotorway(RouteSegmentResult s){
		String h = s.getObject().getHighway();
		return "motorway".equals(h) || "motorway_link".equals(h)  ||
				"trunk".equals(h) || "trunk_link".equals(h);
		
	}

	
	private void attachRoadSegments(RoutingContext ctx, List<RouteSegmentResult> result, int routeInd, int pointInd, boolean plus, boolean recalculation) throws IOException {
		RouteSegmentResult rr = result.get(routeInd);
		RouteDataObject road = rr.getObject();
		long nextL = pointInd < road.getPointsLength() - 1 ? getPoint(road, pointInd + 1) : 0;
		long prevL = pointInd > 0 ? getPoint(road, pointInd - 1) : 0;
		
		// attach additional roads to represent more information about the route
		RouteSegmentResult previousResult = null;
		
		// by default make same as this road id
		long previousRoadId = road.getId();
		if (pointInd == rr.getStartPointIndex() && routeInd > 0) {
			previousResult = result.get(routeInd - 1);
			previousRoadId = previousResult.getObject().getId();
			if (previousRoadId != road.getId()) {
				if (previousResult.getStartPointIndex() < previousResult.getEndPointIndex()
						&& previousResult.getEndPointIndex() < previousResult.getObject().getPointsLength() - 1) {
					rr.attachRoute(pointInd, new RouteSegmentResult(previousResult.getObject(), previousResult.getEndPointIndex(),
							previousResult.getObject().getPointsLength() - 1));
				} else if (previousResult.getStartPointIndex() > previousResult.getEndPointIndex() 
						&& previousResult.getEndPointIndex() > 0) {
					rr.attachRoute(pointInd, new RouteSegmentResult(previousResult.getObject(), previousResult.getEndPointIndex(), 0));
				}
			}
		}
		Iterator<RouteSegment> it;
		if (rr.getPreAttachedRoutes(pointInd) != null) {
			final RouteSegmentResult[] list = rr.getPreAttachedRoutes(pointInd);
			it = new Iterator<BinaryRoutePlanner.RouteSegment>() {
				int i = 0;
				@Override
				public boolean hasNext() {
					return i < list.length;
				}

				@Override
				public RouteSegment next() {
					RouteSegmentResult r = list[i++];
					return new RouteSegment(r.getObject(), r.getStartPointIndex(), r.getEndPointIndex());
				}

				@Override
				public void remove() {
				}
			};	
		} else if (recalculation || ctx.nativeLib == null) {
			RouteSegment rt = ctx.loadRouteSegment(road.getPoint31XTile(pointInd), road.getPoint31YTile(pointInd), ctx.config.memoryLimitation);
			it = rt == null ? null : rt.getIterator();
		} else {
			// Here we assume that all segments should be attached by native
			it = null;
		}
		// try to attach all segments except with current id
		while (it != null && it.hasNext()) {
			RouteSegment routeSegment = it.next();
			if (routeSegment.road.getId() != road.getId() && routeSegment.road.getId() != previousRoadId) {
				RouteDataObject addRoad = routeSegment.road;
				checkAndInitRouteRegion(ctx, addRoad);
				// Future: restrictions can be considered as well
				int oneWay = ctx.getRouter().isOneWay(addRoad);
				if (oneWay >= 0 && routeSegment.getSegmentStart() < addRoad.getPointsLength() - 1) {
					long pointL = getPoint(addRoad, routeSegment.getSegmentStart() + 1);
					if(pointL != nextL && pointL != prevL) {
						// if way contains same segment (nodes) as different way (do not attach it)
						rr.attachRoute(pointInd, new RouteSegmentResult(addRoad, routeSegment.getSegmentStart(), addRoad.getPointsLength() - 1));
					}
				}
				if (oneWay <= 0 && routeSegment.getSegmentStart() > 0) {
					long pointL = getPoint(addRoad, routeSegment.getSegmentStart() - 1);
					// if way contains same segment (nodes) as different way (do not attach it)
					if(pointL != nextL && pointL != prevL) {
						rr.attachRoute(pointInd, new RouteSegmentResult(addRoad, routeSegment.getSegmentStart(), 0));
					}
				}
			}
		}
	}
	
	private static void println(String logMsg) {
//		log.info(logMsg);
		System.out.println(logMsg);
	}
	
	private long getPoint(RouteDataObject road, int pointInd) {
		return (((long) road.getPoint31XTile(pointInd)) << 31) + (long) road.getPoint31YTile(pointInd);
	}
	
	private static double measuredDist(int x1, int y1, int x2, int y2) {
		return MapUtils.getDistance(MapUtils.get31LatitudeY(y1), MapUtils.get31LongitudeX(x1), 
				MapUtils.get31LatitudeY(y2), MapUtils.get31LongitudeX(x2));
	}


}
