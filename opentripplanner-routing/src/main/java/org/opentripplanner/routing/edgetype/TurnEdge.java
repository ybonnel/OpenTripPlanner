/* This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU Lesser General Public License
 as published by the Free Software Foundation, either version 3 of
 the License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>. */

package org.opentripplanner.routing.edgetype;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.onebusaway.gtfs.model.Trip;
import org.opentripplanner.common.geometry.PackedCoordinateSequence;
import org.opentripplanner.routing.core.DirectEdge;
import org.opentripplanner.routing.core.EdgeNarrative;
import org.opentripplanner.routing.core.NoThruTrafficState;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.StateEditor;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseOptions;
import org.opentripplanner.routing.core.Vertex;
import org.opentripplanner.routing.patch.Alert;
import org.opentripplanner.routing.patch.Patch;

import com.vividsolutions.jts.geom.Geometry;

/**
 * An edge between two StreetVertices. This is the most common edge type in the edge-based street
 * graph.
 * 
 */
public class TurnEdge implements DirectEdge, StreetEdge, Serializable {

    public static final String[] DIRECTIONS = { "north", "northeast", "east", "southeast", "south",
            "southwest", "west", "northwest" };

    private static final long serialVersionUID = -4510937090471837118L;

    public int turnCost;

    StreetVertex fromv;

    StreetVertex tov;

    private List<Patch> patches;

    /**
     * If not null, this turn is prohibited to the modes in the set.
     */
    private Set<TraverseMode> restrictedModes;

    /** No-arg constructor used only for customization -- do not call this unless
     * you know what you are doing */
    public TurnEdge() {}
    
    public TurnEdge(StreetVertex fromv, StreetVertex tov) {
        this.fromv = fromv;
        this.tov = tov;
        turnCost = Math.abs(fromv.outAngle - tov.inAngle);
        if (turnCost > 180) {
            turnCost = 360 - turnCost;
        }
    }

    /*
    private static String getDirection(Coordinate a, Coordinate b) {
        double run = b.x - a.x;
        double rise = b.y - a.y;
        double direction = Math.atan2(run, rise);
        int octant = (int) (8 - Math.round(direction * 8 / (Math.PI * 2))) % 8;

        return DIRECTIONS[octant];
    }
    */

    @Override
    public double getDistance() {
        return fromv.getLength();
    }

    @Override
    public Geometry getGeometry() {
        return fromv.getGeometry();
    }

    @Override
    public TraverseMode getMode() {
        return TraverseMode.WALK;
    }

    @Override
    public String getName() {
        return fromv.getName();
    }

    @Override
    public boolean isRoundabout() {
        return fromv.isRoundabout();
    }

    @Override
    public Trip getTrip() {
        return null;
    }

    public State traverse(State s0) {
        return doTraverse(s0, s0.getOptions());
    }

    public State optimisticTraverse(State s0) {
        return doTraverse(s0, s0.getOptions());
    }

    private boolean turnRestricted(TraverseOptions options) {
        if (restrictedModes == null)
            return false;
        else {
            return restrictedModes.contains(options.getModes().getNonTransitMode());
        }
    }

    private State doTraverse(State s0, TraverseOptions options) {
        if (turnRestricted(options) && !options.getModes().contains(TraverseMode.WALK)) {
            return null;
        }
        if (!fromv.canTraverse(options)) {
            if (options.getModes().contains(TraverseMode.BICYCLE)) {
                // try walking bicycle, since you can't ride it here
                return doTraverse(s0, options.getWalkingOptions());
            }
            return null;
        }

        TraverseMode traverseMode = options.getModes().getNonTransitMode();

        EdgeNarrative en = new FixedModeEdge(this, traverseMode);
        StateEditor s1 = s0.edit(this, en);

        switch (s0.getNoThruTrafficState()) {
        case INIT:
            if (fromv.isNoThruTraffic()) {
                s1.setNoThruTrafficState(NoThruTrafficState.IN_INITIAL_ISLAND);
            } else {
                s1.setNoThruTrafficState(NoThruTrafficState.BETWEEN_ISLANDS);
            }
            break;
        case IN_INITIAL_ISLAND:
            if (!fromv.isNoThruTraffic()) {
                s1.setNoThruTrafficState(NoThruTrafficState.BETWEEN_ISLANDS);
            }
            break;
        case BETWEEN_ISLANDS:
            if (fromv.isNoThruTraffic()) {
                s1.setNoThruTrafficState(NoThruTrafficState.IN_FINAL_ISLAND);
            }
            break;
        case IN_FINAL_ISLAND:
            if (!fromv.isNoThruTraffic()) {
                // we have now passed entirely through a no thru traffic region,
                // which is
                // forbidden
                return null;
            }
            break;
        }

        double time = (fromv.getEffectiveLength(traverseMode) + turnCost / 20.0) / options.speed;
        double weight = fromv.computeWeight(s0, options, time);
        s1.incrementWalkDistance(fromv.getLength());
        s1.incrementTimeInSeconds((int) Math.ceil(time));
        s1.incrementWeight(weight);
        if (s1.weHaveWalkedTooFar(options))
            return null;

        return s1.makeState();
    }

    public String toString() {
        return "TurnEdge( " + fromv + ", " + tov + ")";
    }

    public PackedCoordinateSequence getElevationProfile() {
        return fromv.getElevationProfile();
    }

    @Override
    public Vertex getFromVertex() {
        return fromv;
    }

    @Override
    public Vertex getToVertex() {
        return tov;
    }

    @Override
    public boolean canTraverse(TraverseOptions options) {
    	if (turnRestricted(options) && !options.getModes().contains(TraverseMode.WALK)) {
    		return false;
    	}
        return fromv.canTraverse(options);
    }

    @Override
    public double getLength() {
        return fromv.getLength();
    }

    @Override
    public PackedCoordinateSequence getElevationProfile(double start, double end) {
        return fromv.getElevationProfile(start, end);
    }

    @Override
    public StreetTraversalPermission getPermission() {
        return fromv.getPermission();
    }

    @Override
    public void setElevationProfile(PackedCoordinateSequence elev) {
        fromv.setElevationProfile(elev);
    }
    
    public boolean equals(Object o) {
        if (o instanceof TurnEdge) {
            TurnEdge other = (TurnEdge) o;
            return other.fromv.equals(fromv) && other.getToVertex().equals(tov);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return fromv.hashCode() * 31 + tov.hashCode();
    }

    @Override
    public void addPatch(Patch patch) {
        if (patches == null) {
            patches = new ArrayList<Patch>();
        }
        patches.add(patch);
    }

    @Override
    public List<Patch> getPatches() {
        if (patches == null) {
            return Collections.emptyList();
        }
        return patches;
    }

    @Override
    public void removePatch(Patch patch) {
        if (patches.size() == 1) {
            patches = null;
        } else {
            patches.remove(patch);
        }
    }

    @Override
    public Set<Alert> getNotes() {
        return fromv.getNotes();
    }

    public void setRestrictedModes(Set<TraverseMode> modes) {
        this.restrictedModes = modes;
    }

    public Set<TraverseMode> getRestrictedModes() {
        return restrictedModes;
    }

    @Override
    public boolean hasBogusName() {
        return fromv.hasBogusName();
    }

    @Override
    public boolean isNoThruTraffic() {
        return fromv.isNoThruTraffic();
    }

    @Override
    public double weightLowerBound(TraverseOptions options) {
        return timeLowerBound(options) * options.walkReluctance;
    }
    
    @Override
    public double timeLowerBound(TraverseOptions options) {
        return (fromv.length + turnCost/20) / options.speed;
    }
    
}
