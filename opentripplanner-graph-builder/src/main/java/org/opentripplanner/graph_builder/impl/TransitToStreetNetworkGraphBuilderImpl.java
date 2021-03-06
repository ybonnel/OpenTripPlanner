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

package org.opentripplanner.graph_builder.impl;

import org.opentripplanner.graph_builder.services.GraphBuilder;
import org.opentripplanner.routing.core.Graph;
import org.opentripplanner.routing.edgetype.loader.NetworkLinker;

/**
 * {@link GraphBuilder} plugin that links up the stops of a transit network to a street network.
 * Should be called after both the transit network and street network are loaded.
 */
public class TransitToStreetNetworkGraphBuilderImpl implements GraphBuilder {

    @Override
    public void buildGraph(Graph graph) {
        NetworkLinker linker = new NetworkLinker(graph);
        linker.createLinkage();
    }

}
