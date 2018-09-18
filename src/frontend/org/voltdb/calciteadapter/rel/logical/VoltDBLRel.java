/* This file is part of VoltDB.
 * Copyright (C) 2008-2018 VoltDB Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.calciteadapter.rel.logical;

import org.apache.calcite.plan.Convention;
import org.apache.calcite.rel.RelNode;

/**
 * From Mike A. A RelNode interface added with calling convention trait.
 */
public interface VoltDBLRel extends RelNode  {
    final static Convention VOLTDB_LOGICAL = new Convention.Impl("VOLTDB_LOGICAL", VoltDBLRel.class);

}
