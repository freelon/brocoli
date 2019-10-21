package de.upb.cs.brocoli.library

// The communications module provides the internal implementations of the ServiceHandler.
// There is an online and an offline module. Every new message is passed through the offline module.
// The offline module then checks if the message should be passed to the online module, in case a
// connection to the server is available.

// The online module registers for Firebase callbacks and creates a connection with the server when
// demanded. It then exchanges message data with the server.
//
// It can also do that in regular intervals to exchange information in the distributed database.
//

// Initially we will ignore the online module and just implement the offline module, until we have
// decided on the best way to connect those two.