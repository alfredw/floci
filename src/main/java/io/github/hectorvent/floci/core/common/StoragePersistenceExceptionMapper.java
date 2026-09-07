package io.github.hectorvent.floci.core.common;

import io.github.hectorvent.floci.core.storage.StoragePersistenceException;
import io.github.hectorvent.floci.services.s3.S3Controller;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

@Provider
public class StoragePersistenceExceptionMapper implements ExceptionMapper<StoragePersistenceException> {
    private static final Logger LOG = Logger.getLogger(StoragePersistenceExceptionMapper.class);
    @Context ResourceInfo resourceInfo;

    @Override
    public Response toResponse(StoragePersistenceException exception) {
        LOG.error("Persistent storage failed; repair storage and restart", exception);
        if (resourceInfo != null && S3Controller.class.equals(resourceInfo.getResourceClass())) {
            return Response.status(500).type(MediaType.APPLICATION_XML)
                    .entity(new XmlBuilder().start("Error").elem("Code", "InternalError")
                            .elem("Message", exception.getMessage()).end("Error").build()).build();
        }
        return Response.status(500).type(MediaType.APPLICATION_JSON)
                .entity(new AwsErrorResponse("InternalError", exception.getMessage())).build();
    }
}
