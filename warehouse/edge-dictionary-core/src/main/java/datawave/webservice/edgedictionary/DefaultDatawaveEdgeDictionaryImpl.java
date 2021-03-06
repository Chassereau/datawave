package datawave.webservice.edgedictionary;

import com.google.common.collect.SetMultimap;
import com.google.protobuf.InvalidProtocolBufferException;
import datawave.configuration.spring.SpringBean;
import datawave.edge.protobuf.EdgeData.MetadataValue;
import datawave.edge.util.EdgeKey;
import datawave.query.util.MetadataHelper;
import datawave.query.util.MetadataHelperFactory;
import datawave.util.StringUtils;
import datawave.webservice.results.edgedictionary.DefaultEdgeDictionary;
import datawave.webservice.results.edgedictionary.DefaultMetadata;
import datawave.webservice.results.edgedictionary.EventField;
import org.apache.accumulo.core.client.Connector;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.hadoop.io.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;

/**
 * 
 */
public class DefaultDatawaveEdgeDictionaryImpl implements DatawaveEdgeDictionary {
    private static final Logger log = LoggerFactory.getLogger(DefaultDatawaveEdgeDictionaryImpl.class);
    
    @Inject
    @SpringBean(refreshable = true)
    protected MetadataHelperFactory metadataHelperFactory;
    
    /*
     * (non-Javadoc)
     * 
     * @see datawave.webservice.edgedictionary.DatawaveEdgeDictionary#getFields (java.lang.String, java.lang.String, java.lang.String,
     * org.apache.accumulo.core.client.Connector, accumulo.core.security.Authorizations, int)
     */
    @Override
    public DefaultEdgeDictionary getEdgeDictionary(String metadataTableName, Connector connector, Set<Authorizations> auths, int numThreads) throws Exception {
        
        MetadataHelper metadataHelper = this.metadataHelperFactory.createMetadataHelper();
        
        metadataHelper.initialize(connector, metadataTableName, auths);
        
        // Convert them into a response object
        
        // Convert them into the DataDictionary response object
        return transformResults(metadataHelper.getEdges());
    }
    
    public void setMetadataHelperFactory(MetadataHelperFactory metadataHelperFactory) {
        this.metadataHelperFactory = metadataHelperFactory;
    }
    
    // consume the iterator, parse key/value pairs into Metadata stuff
    private DefaultEdgeDictionary transformResults(SetMultimap<Key,Value> edgeMetadataRows) {
        final Text row = new Text(), cf = new Text(), cq = new Text();
        
        Collection<DefaultMetadata> metadata = new LinkedList<>();
        // Each Entry is the entire row
        for (Entry<Key,Value> edgeMetadataRow : edgeMetadataRows.entries()) {
            DefaultMetadata meta = new DefaultMetadata();
            String startDate = null; // Earliest date of collection
            
            // Handle batch scanner bug
            if (edgeMetadataRow.getKey() == null && edgeMetadataRow.getValue() == null)
                return null;
            if (null == edgeMetadataRow.getKey() || null == edgeMetadataRow.getValue()) {
                throw new IllegalArgumentException("Null key or value. Key:" + edgeMetadataRow.getKey() + ", Value: " + edgeMetadataRow.getValue());
            }
            
            Key key = edgeMetadataRow.getKey();
            Value value = edgeMetadataRow.getValue();
            
            // Parse row/cf/cq for
            // TODO create meta key / value helper classes
            key.getRow(row);
            key.getColumnFamily(cf);
            key.getColumnQualifier(cq);
            
            String[] pieces = StringUtils.split(row.toString(), EdgeKey.COL_SEPARATOR);
            if (pieces.length != 2) {
                throw new IllegalArgumentException("Invalid Edge Metadata Key:" + edgeMetadataRow.getKey());
            }
            meta.setEdgeType(pieces[0]);
            meta.setEdgeRelationship(pieces[1]);
            meta.setEdgeAttribute1Source(cq.toString());
            
            // Parse the Value
            MetadataValue metadataVal;
            try {
                metadataVal = datawave.edge.protobuf.EdgeData.MetadataValue.parseFrom(value.get());
            } catch (InvalidProtocolBufferException e) {
                log.error("Found invalid Edge Metadata Value bytes.");
                continue;
            }
            List<EventField> eFields = new LinkedList<>();
            for (MetadataValue.Metadata metaval : metadataVal.getMetadataList()) {
                EventField eField = new EventField();
                eField.setSourceField(metaval.getSource());
                eField.setSinkField(metaval.getSink());
                if (metaval.hasEnrichment()) {
                    eField.setEnrichmentField(metaval.getEnrichment());
                    eField.setEnrichmentIndex(metaval.getEnrichmentIndex());
                }
                
                if (metaval.hasJexlPrecondition()) {
                    eField.setJexlPrecondition(metaval.getJexlPrecondition());
                }
                eFields.add(eField);
                
                if (metaval.hasDate()) {
                    if (startDate == null) {
                        startDate = metaval.getDate();
                    } else if (startDate.compareTo(metaval.getDate()) > 0) {
                        startDate = metaval.getDate();
                    }
                }
            }
            
            meta.setStartDate(startDate);
            meta.setEventFields(eFields);
            metadata.add(meta);
        }
        return new DefaultEdgeDictionary(metadata);
    }
}
