/*
 * Copyright 2016 Axel Faust
 *
 * Licensed under the Eclipse Public License (EPL), Version 1.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.eclipse.org/legal/epl-v10.html
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package de.axelfaust.alfresco.simplecontentstores.repo.store;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.WritableByteChannel;
import java.util.Collection;

import org.alfresco.repo.content.AbstractContentWriter;
import org.alfresco.repo.content.ContentContext;
import org.alfresco.repo.content.ContentStore;
import org.alfresco.repo.content.MimetypeMap;
import org.alfresco.repo.content.filestore.FileContentWriter;
import org.alfresco.service.cmr.repository.ContentIOException;
import org.alfresco.service.cmr.repository.ContentReader;
import org.alfresco.service.cmr.repository.ContentStreamListener;
import org.alfresco.service.cmr.repository.ContentWriter;
import org.alfresco.service.cmr.repository.MimetypeService;
import org.alfresco.service.cmr.repository.MimetypeServiceAware;
import org.alfresco.util.ParameterCheck;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.utils.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Axel Faust
 */
// TODO Refactor into a proper facade similar to EncryptingContentWriterFacade
public class CompressingContentWriter extends AbstractContentWriter implements ContentStreamListener
{

    private static final Logger LOGGER = LoggerFactory.getLogger(CompressingContentWriter.class);

    private static final CompressorStreamFactory COMPRESSOR_STREAM_FACTORY = new CompressorStreamFactory();

    protected final ContentContext context;

    protected final ContentStore temporaryContentStore;

    protected final ContentWriter backingWriter;

    protected final ContentWriter temporaryWriter;

    protected final String compressionType;

    protected final Collection<String> mimetypesToCompress;

    protected boolean writtenToBackingWriter = false;

    protected MimetypeService mimetypeService;

    protected CompressingContentWriter(final String contentUrl, final ContentContext context, final ContentStore temporaryContentStore,
            final ContentWriter backingWriter, final String compressionType, final Collection<String> mimetypesToCompress)
    {
        super(backingWriter.getContentUrl() != null ? backingWriter.getContentUrl() : context.getContentUrl(), context
                .getExistingContentReader());

        ParameterCheck.mandatory("context", context);
        ParameterCheck.mandatory("temporaryContentStore", temporaryContentStore);
        ParameterCheck.mandatory("backingWriter", backingWriter);

        this.context = context;
        this.temporaryContentStore = temporaryContentStore;
        this.backingWriter = backingWriter;

        this.compressionType = compressionType;
        this.mimetypesToCompress = mimetypesToCompress;

        // we are the first real listener (DoGuessingOnCloseListener always is first)
        super.addListener(this);

        final ContentContext temporaryContext = new ContentContext(context.getExistingContentReader(), null);
        this.temporaryWriter = this.temporaryContentStore.getWriter(temporaryContext);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getSize()
    {
        final long size = this.writtenToBackingWriter ? this.backingWriter.getSize() : this.temporaryWriter.getSize();
        return size;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void contentStreamClosed()
    {
        this.writeToBackingStore();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setMimetypeService(final MimetypeService mimetypeService)
    {
        this.mimetypeService = mimetypeService;
        super.setMimetypeService(mimetypeService);

        if (this.backingWriter instanceof MimetypeServiceAware)
        {
            ((MimetypeServiceAware) this.backingWriter).setMimetypeService(mimetypeService);
        }

        if (this.temporaryWriter instanceof MimetypeServiceAware)
        {
            ((MimetypeServiceAware) this.temporaryWriter).setMimetypeService(mimetypeService);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected ContentReader createReader() throws ContentIOException
    {
        ContentReader reader;

        if (this.writtenToBackingWriter)
        {
            reader = new DecompressingContentReader(this.backingWriter.getReader(), this.compressionType, this.mimetypesToCompress);
        }
        else
        {
            reader = this.temporaryWriter.getReader();
        }
        return reader;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected WritableByteChannel getDirectWritableChannel() throws ContentIOException
    {
        // need to wrap this to avoid issue of CallbackFileChannel rejection in CallbackFileChannel constructor
        final WritableByteChannel channel = new WritableByteChannel()
        {

            private final WritableByteChannel channel = CompressingContentWriter.this.temporaryWriter.getWritableChannel();

            @Override
            public boolean isOpen()
            {
                return this.channel.isOpen();
            }

            @Override
            public void close() throws IOException
            {
                this.channel.close();
            }

            @Override
            public int write(final ByteBuffer src) throws IOException
            {
                return this.channel.write(src);
            }
        };
        return channel;
    }

    protected void writeToBackingStore()
    {
        String mimetype = this.getMimetype();
        LOGGER.debug("Determined mimetype {} from write into temporary store", mimetype);

        if ((this.mimetypesToCompress != null && !this.mimetypesToCompress.isEmpty()) && this.mimetypeService != null
                && (mimetype == null || MimetypeMap.MIMETYPE_BINARY.equals(mimetype)))
        {
            mimetype = this.mimetypeService.guessMimetype(null, this.createReader());
            LOGGER.debug("Determined mimetype {} from MimetypeService.guessMimetype()", mimetype);

            if (mimetype == null || MimetypeMap.MIMETYPE_BINARY.equals(mimetype))
            {
                this.setMimetype(mimetype);
            }
        }

        try
        {
            final boolean shouldCompress = this.mimetypesToCompress == null
                    || this.mimetypesToCompress.isEmpty()
                    || (mimetype != null && (this.mimetypesToCompress.contains(mimetype) || this
                            .isMimetypeToCompressWildcardMatch(mimetype)));
            if (shouldCompress)
            {
                LOGGER.debug("Content will be compressed to backing store (url={})", this.getContentUrl());
                final String compressiongType = this.compressionType != null && !this.compressionType.trim().isEmpty() ? this.compressionType
                        : CompressorStreamFactory.GZIP;
                try (final OutputStream contentOutputStream = this.backingWriter.getContentOutputStream())
                {
                    try (OutputStream compressedOutputStream = COMPRESSOR_STREAM_FACTORY.createCompressorOutputStream(compressiongType,
                            contentOutputStream))
                    {
                        final ContentReader reader = this.createReader();
                        try (final InputStream contentInputStream = reader.getContentInputStream())
                        {
                            IOUtils.copy(contentInputStream, compressedOutputStream);
                        }
                    }
                }
                catch (final IOException | CompressorException ex)
                {
                    throw new ContentIOException("Error writing compressed content", ex);
                }
            }
            else
            {
                LOGGER.debug("Content will not be compressed to backing store (url={})", this.getContentUrl());
                this.backingWriter.putContent(this.createReader());
            }

            final String finalContentUrl = this.backingWriter.getContentUrl();
            // we don't expect a different content URL, but just to make sure
            this.setContentUrl(finalContentUrl);
        }
        finally
        {
            this.writtenToBackingWriter = true;
        }
    }

    protected void cleanupTemporaryContent()
    {
        // check if we can trigger eager clean up
        // (standard temp lifetime of between 1:00 and 1:59 hours just causes too much build-up)
        if (this.temporaryWriter instanceof FileContentWriter)
        {
            final File tempFile = ((FileContentWriter) this.temporaryWriter).getFile();
            if (tempFile.exists() && !tempFile.delete())
            {
                tempFile.deleteOnExit();
            }
        }
        else
        {
            try
            {
                this.temporaryContentStore.delete(this.temporaryWriter.getContentUrl());
            }
            catch (final UnsupportedOperationException uoe)
            {
                LOGGER.debug("Temporary content store does not support delete", uoe);
            }
        }
    }

    protected boolean isMimetypeToCompressWildcardMatch(final String mimetype)
    {
        boolean isMatch = false;
        for (final String mimetypeToCompress : this.mimetypesToCompress)
        {
            if (mimetypeToCompress.endsWith("/*"))
            {
                if (mimetype.startsWith(mimetypeToCompress.substring(0, mimetypeToCompress.length() - 1)))
                {
                    isMatch = true;
                    break;
                }
            }
        }
        return isMatch;
    }
}
