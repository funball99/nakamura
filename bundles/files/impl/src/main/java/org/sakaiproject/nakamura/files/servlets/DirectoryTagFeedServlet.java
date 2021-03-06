/**
 * Licensed to the Sakai Foundation (SF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.sakaiproject.nakamura.files.servlets;

import static org.sakaiproject.nakamura.api.files.FilesConstants.RT_SAKAI_TAG;

import com.google.common.collect.ImmutableMap;

import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.sling.SlingServlet;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.io.JSONWriter;
import org.apache.solr.client.solrj.util.ClientUtils;
import org.sakaiproject.nakamura.api.doc.BindingType;
import org.sakaiproject.nakamura.api.doc.ServiceBinding;
import org.sakaiproject.nakamura.api.doc.ServiceDocumentation;
import org.sakaiproject.nakamura.api.doc.ServiceExtension;
import org.sakaiproject.nakamura.api.doc.ServiceMethod;
import org.sakaiproject.nakamura.api.doc.ServiceParameter;
import org.sakaiproject.nakamura.api.doc.ServiceResponse;
import org.sakaiproject.nakamura.api.doc.ServiceSelector;
import org.sakaiproject.nakamura.api.files.FilesConstants;
import org.sakaiproject.nakamura.api.lite.Repository;
import org.sakaiproject.nakamura.api.lite.content.Content;
import org.sakaiproject.nakamura.api.lite.content.ContentManager;
import org.sakaiproject.nakamura.api.profile.ProfileService;
import org.sakaiproject.nakamura.api.search.SearchServiceFactory;
import org.sakaiproject.nakamura.api.search.solr.Query;
import org.sakaiproject.nakamura.api.search.solr.Result;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchBatchResultProcessor;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchException;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchResultSet;
import org.sakaiproject.nakamura.api.search.solr.SolrSearchServiceFactory;
import org.sakaiproject.nakamura.files.search.LiteFileSearchBatchResultProcessor;
import org.sakaiproject.nakamura.util.ExtendedJSONWriter;
import org.sakaiproject.nakamura.util.PathUtils;
import org.sakaiproject.nakamura.util.ServletUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

@SlingServlet(extensions = { "json" }, generateComponent = true, generateService = true,
  methods = { "GET" }, resourceTypes = { "sakai/directory" },
  selectors = {"tagged"}
)
@Properties(value = {
  @Property(name = "service.description", value = "Provides support for file tagging."),
  @Property(name = "service.vendor", value = "The Sakai Foundation")
})
@ServiceDocumentation(name = "DirectoryTagFeedServlet", okForVersion = "1.2",
  shortDescription = "Get sample content items for all directory tags.",
  description = {
  "This servlet responds to requests on content of type 'sakai/directory', using the 'tagged' selector.",
  "For example: <pre>/tags/directory.tagged.json</pre>",
  "The result is a feed of sample content items, one item per category in the given directory.",
  "This can be used as a preview of the different types and categories of content in the system."
},
  bindings = {
@ServiceBinding(type = BindingType.TYPE, bindings = { "sakai/directory" },
extensions = @ServiceExtension(name = "json", description = "This servlet outputs JSON data."),
selectors = {
@ServiceSelector(name = "tagged", description = "Will dump all the children of this tag.")
  }
  )
},
  methods = {
@ServiceMethod(name = "GET", description = { "This servlet only responds to GET requests." },
response = {
@ServiceResponse(code = 200, description = "Successful request, json can be found in the body"),
@ServiceResponse(code = 500, description = "Failure to retrieve tags or files, an explanation can be found in the HTMl.")
  }
  )
}
)
public class DirectoryTagFeedServlet extends SlingSafeMethodsServlet {
  private static final long serialVersionUID = -8815248520601921760L;

  @Reference
  protected transient SearchServiceFactory searchServiceFactory;

  @Reference
  protected transient SolrSearchServiceFactory solrSearchServiceFactory;
  
  @Reference
  protected transient Repository sparseRepository;
  
  @Reference
  private ProfileService profileService;

  @Reference
  protected LiteFileSearchBatchResultProcessor liteFileSearchBatchResultProcessor;

  /**
   * {@inheritDoc}
   *
   * @see org.apache.sling.api.servlets.SlingSafeMethodsServlet#doGet(org.apache.sling.api.SlingHttpServletRequest,
   *      org.apache.sling.api.SlingHttpServletResponse)
   */
  @Override
  protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
      throws ServletException, IOException {
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    // digest the selectors to determine if we should send a tidy result
    // or if we need to traverse deeper into the tagged node.
    boolean tidy = false;
    int depth = 0;
    String[] selectors = request.getRequestPathInfo().getSelectors();
    for (String sel : selectors) {
      if ("tidy".equals(sel)) {
        tidy = true;
      } else if ("infinity".equals(sel)) {
        depth = -1;
      } else {
        // check if the selector is telling us the depth of detail to return
        Integer d = null;
        try { d = Integer.parseInt(sel); } catch (NumberFormatException e) {}
        if (d != null) {
          depth = d;
        }
      }
    }
    
    request.setAttribute("depth", depth);
    JSONWriter write = new JSONWriter(response.getWriter());
    write.setTidy(ServletUtils.isTidy(request));
    Resource directoryResource = request.getResource();
    try {
      write.object();
      Content directory = directoryResource.adaptTo(Content.class);
      if (directory != null) {
        ContentManager cm = directoryResource.adaptTo(ContentManager.class);
        Iterator<Content> children = cm.listChildren(directoryResource.getPath());
        while (children.hasNext()) {
          Content child = children.next();
          write.key(PathUtils.lastElement(child.getPath()));
          write.object();
          ExtendedJSONWriter.writeNodeContentsToWriter(write, child);
          write.key("content");
          writeOneTaggedItemForTags(request, getTagsForDirectoryBranch(child, cm, request), write);
          write.endObject();
        }
      }
      write.endObject();
    } catch (Exception e) {
      response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
    }
  }

  private List<String> getTagsForDirectoryBranch(Content branch, ContentManager cm,
      SlingHttpServletRequest request) throws SolrSearchException {
    String queryString = "path:" + ClientUtils.escapeQueryChars(branch.getPath())
        + " AND resourceType:" + ClientUtils.escapeQueryChars(RT_SAKAI_TAG);
    ArrayList<String> rv = new ArrayList<String>();

    Query solrQuery = new Query(queryString, ImmutableMap.<String, Object>of());
    SolrSearchResultSet rs = solrSearchServiceFactory.getSearchResultSet(request, solrQuery);
    Iterator<Result> results = rs.getResultSetIterator();
    while (results.hasNext()) {
      Result result = results.next();
      rv.add(String.valueOf(result.getFirstValue("tagname")));
    }

    return rv;
  }

  private void writeOneTaggedItemForTags(SlingHttpServletRequest request,
      List<String> tags, JSONWriter write) throws JSONException, SolrSearchException {
    if (tags == null || tags.size() == 0) {
      write.object().endObject();
      return;
    }

    // BL120 KERN-1617 Need to include Content tagged with tag uuid
    final StringBuilder sb = new StringBuilder();
    sb.append("tag:(");
    String sep = "";
    for (String tag : tags) {
      sb.append(sep).append(ClientUtils.escapeQueryChars(tag));
      sep = " ";
    }
    sb.append(") AND resourceType:").append(ClientUtils.escapeQueryChars(FilesConstants.POOLED_CONTENT_RT));
    final int random = (int) (Math.random() * 10000);
    String sortRandom = "random_" + String.valueOf(random) + " asc";
    final String queryString = sb.toString();
    Query solrQuery = new Query(queryString, ImmutableMap.of("sort", (Object) sortRandom));

    final SolrSearchResultSet srs = liteFileSearchBatchResultProcessor.getSearchResultSet(request, solrQuery);
    if (srs.getResultSetIterator().hasNext()) {
      liteFileSearchBatchResultProcessor.writeResults(request, write, selectOneResult(srs.getResultSetIterator()));
    } else {
      // write an empty result
      write.object().endObject();
    }
  }

  private Iterator<Result> selectOneResult(Iterator<Result> resultSetIterator) {
    Result bestResult = null;
    while(resultSetIterator.hasNext()) {
      Result result = resultSetIterator.next();
      bestResult = result;
      if (isBest(result)) {
        break;
      }
    }
    
    final Result finalResult = bestResult;
    
    return new Iterator<Result>() {
      boolean hasBeenRetrieved = false;

      public boolean hasNext() {
        return !hasBeenRetrieved;
      }

      public Result next() {
        if (hasBeenRetrieved) {
          throw new NoSuchElementException();
        } else {
          hasBeenRetrieved = true;
          return finalResult;
        }
      }

      public void remove() {
        if (hasBeenRetrieved) {
          throw new NoSuchElementException();
        } else {
          hasBeenRetrieved = true;
        }
      }

    };
  }

  private boolean isBest(Result result) {
    return (result.getFirstValue("description") != null );
  }

}
