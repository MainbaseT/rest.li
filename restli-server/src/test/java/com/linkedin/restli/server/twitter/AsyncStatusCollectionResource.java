/*
   Copyright (c) 2012 LinkedIn Corp.

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/

package com.linkedin.restli.server.twitter;

import com.linkedin.restli.server.BatchCreateRequest;
import com.linkedin.restli.server.BatchCreateResult;
import com.linkedin.restli.server.BatchDeleteRequest;
import com.linkedin.restli.server.BatchPatchRequest;
import com.linkedin.restli.server.BatchUpdateRequest;
import com.linkedin.restli.server.BatchUpdateResult;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.linkedin.common.callback.Callback;
import com.linkedin.restli.common.PatchRequest;
import com.linkedin.restli.server.CreateResponse;
import com.linkedin.restli.server.PagingContext;
import com.linkedin.restli.server.UpdateResponse;
import com.linkedin.restli.server.annotations.CallbackParam;
import com.linkedin.restli.server.annotations.Context;
import com.linkedin.restli.server.annotations.Finder;
import com.linkedin.restli.server.annotations.Optional;
import com.linkedin.restli.server.annotations.QueryParam;
import com.linkedin.restli.server.annotations.RestLiCollection;
import com.linkedin.restli.server.resources.CollectionResourceAsyncTemplate;
import com.linkedin.restli.server.twitter.TwitterTestDataModels.Status;
import com.linkedin.restli.server.twitter.TwitterTestDataModels.StatusType;

/**
 * CollectionResource containing all statuses.
 *
 * @author femekci
 */
@RestLiCollection(name="asyncstatuses",
                    keyName="statusID")
public class AsyncStatusCollectionResource extends
    CollectionResourceAsyncTemplate<Long, Status>
{
  /**
   * Gets a sample of the timeline of statuses generated by all users.
   */
  @Finder("public_timeline")
  public void getPublicTimeline(@Context final PagingContext pagingContext,
                                @CallbackParam final Callback<List<Status>> callback)
  {
    callback.onSuccess(null);
  }

  /**
   * Gets the status timeline for a given user.
   */
  @Finder("user_timeline")
  public void getUserTimeline(@Context final PagingContext pagingContext,
                              @Optional("true") @QueryParam("includeReplies") final Boolean includeReplies,
                              @CallbackParam final Callback<List<Status>> callback)
  {
    callback.onSuccess(null);
  }

  /**
   * Keyword search for statuses.
   *
   * @param keywords keywords to search for
   * @param since a unix timestamp. If present, only statuses created after this time are
   *          returned
   */

  @Finder("search")
  public void search(@Context final PagingContext pagingContext,
                             @QueryParam("keywords") final String keywords,
                             @QueryParam("since") @Optional("-1") final long since,
                             @QueryParam("type") @Optional final StatusType type,
                             @CallbackParam final Callback<List<Status>> callback)
  {
    callback.onSuccess(null);
  }

  /**
   * Creates a new Status.
   */
  @Override
  public void create(final Status entity,
                     @CallbackParam final Callback<CreateResponse> callback)
  {
    callback.onSuccess(null);
  }

  /**
   * Creates new Statuses.
   *
   * @param entities
   * @param callback
   */
  @Override
  public void batchCreate(BatchCreateRequest<Long, Status> entities,
                          @CallbackParam final Callback<BatchCreateResult<Long, Status>> callback)
  {
    callback.onSuccess(null);
  }

  /**
   * Deletes Statuses.
   *
   * @param ids
   * @param callback
   */
  @Override
  public void batchDelete(BatchDeleteRequest<Long, Status> ids,
                          @CallbackParam final Callback<BatchUpdateResult<Long, Status>> callback)
  {
    callback.onSuccess(null);
  }

  /**
   * Updates Statuses.
   *
   * @param entities
   * @param callback
   */
  @Override
  public void batchUpdate(BatchUpdateRequest<Long, Status> entities,
                          @CallbackParam final Callback<BatchUpdateResult<Long, Status>> callback)
  {
    callback.onSuccess(null);
  }

  /**
   * Patches Statuses.
   *
   * @param patches
   * @param callback
   */
  @Override
  public void batchUpdate(BatchPatchRequest<Long, Status> patches,
                          @CallbackParam final Callback<BatchUpdateResult<Long, Status>> callback)
  {
    callback.onSuccess(null);
  }

  /**
   * Gets a batch of statuses.
   */
  @Override
  public void batchGet(final Set<Long> ids,
                       @CallbackParam final Callback<Map<Long, Status>> callback)
  {
    callback.onSuccess(null);
  }

  /**
   * Gets a single status resource.
   */
  @Override
  public void get(final Long key, @CallbackParam final Callback<Status> callback)
  {
    callback.onSuccess(null);
  }

  /**
   * Deletes a status resource.
   */
  @Override
  public void delete(final Long key,
                     @CallbackParam final Callback<UpdateResponse> callback)
  {
    callback.onSuccess(null);
  }

  /**
   * Partially updates a single status resource.
   */
  @Override
  public void update(final Long key,
                     final PatchRequest<Status> request,
                     @CallbackParam final Callback<UpdateResponse> callback)
  {
    callback.onSuccess(null);
  }

  /**
   * Updates (overwrites) a single status resource.
   */
  @Override
  public void update(final Long key,
                     final Status entity,
                     @CallbackParam final Callback<UpdateResponse> callback)
  {
    callback.onSuccess(null);
  }

  @Override
  public void getAll(@Context PagingContext ctx,
                     @CallbackParam Callback<List<Status>> callback)
  {
    callback.onSuccess(null);
  }
}
