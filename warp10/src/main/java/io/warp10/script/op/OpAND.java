//
//   Copyright 2016  Cityzen Data
//
//   Licensed under the Apache License, Version 2.0 (the "License");
//   you may not use this file except in compliance with the License.
//   You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
//   Unless required by applicable law or agreed to in writing, software
//   distributed under the License is distributed on an "AS IS" BASIS,
//   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//   See the License for the specific language governing permissions and
//   limitations under the License.
//

package io.warp10.script.op;

import io.warp10.continuum.gts.GeoTimeSerie;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptNAryFunction;
import io.warp10.script.WarpScriptException;

import java.util.Map;

/**
 * AND values from multiple time series. The elevation and location are cleared.
 */
public class OpAND extends NamedWarpScriptFunction implements WarpScriptNAryFunction {
  
  /**
   * Should 'null' values (i.e. missing) be forbidden or ignored
   */
  private final boolean forbidNulls;
  
  public OpAND(String name, boolean forbidNulls) {
    super(name);
    this.forbidNulls = forbidNulls;
  }
  
  @Override
  public Object apply(Object[] args) throws WarpScriptException {
    long tick = (long) args[0];
    String[] names = (String[]) args[1];
    Map<String,String>[] labels = (Map<String,String>[]) args[2];
    long[] ticks = (long[]) args[3];
    long[] locations = (long[]) args[4];
    long[] elevations = (long[]) args[5];
    Object[] values = (Object[]) args[6];
    
    //
    // The type of result is determined by the first non null value
    //
    
    boolean and = true;
    
    long location = GeoTimeSerie.NO_LOCATION;
    long elevation = GeoTimeSerie.NO_ELEVATION;
    
    for (int i = 0; i < values.length; i++) {      
      // If one of the values is 'null' (absent), return null as the value
      if (null == values[i]) {
        if (this.forbidNulls) {
          return new Object[] { tick, location, elevation, null };
        } else {
          // Simply ignore nulls
          continue;
        }
      }      
      
      and = and && Boolean.TRUE.equals(values[i]);
      
      if (!and) {
        return new Object[] { tick, location, elevation, false };
      }
    }
    
    return new Object[] { tick, location, elevation, true };
  }
}
