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

package io.warp10.script.unary;

import io.warp10.continuum.store.Constants;
import io.warp10.script.NamedWarpScriptFunction;
import io.warp10.script.WarpScriptException;
import io.warp10.script.WarpScriptStack;
import io.warp10.script.WarpScriptStackFunction;

import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;

/**
 * Convert Date in format ISO8601 into a Timestamp in µs
 *
 * TOTIMESTAMP expects a date in ISO8601 on the top of the stack
 */
public class TOTIMESTAMP extends NamedWarpScriptFunction implements WarpScriptStackFunction {
  public TOTIMESTAMP(String name) {
    super(name);
  }

  @Override
  public Object apply(WarpScriptStack stack) throws WarpScriptException {
    Object top = stack.pop();


    if (!(top instanceof String)) {
      throw new WarpScriptException(getName() + " expects an ISO8601 timestamp on top of the stack.");
    } else {
      try {
        ZonedDateTime zdt = ZonedDateTime.parse(top.toString());
                
        long ts = zdt.getLong(ChronoField.INSTANT_SECONDS) * Constants.TIME_UNITS_PER_S + zdt.getLong(ChronoField.NANO_OF_SECOND) / (1000000L / Constants.TIME_UNITS_PER_MS);
        stack.push(ts);
      } catch (DateTimeParseException dtpe) {
        throw new WarpScriptException(getName() + " expects an ISO8601 timestamp (YYYY-MM-DDThh:mm:ss.sssssssss) with a specified time zone.", dtpe);
      }
    }

    return stack;
  }
}